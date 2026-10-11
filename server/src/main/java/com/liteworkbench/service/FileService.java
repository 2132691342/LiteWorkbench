package com.liteworkbench.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.config.StorageProperties;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.entity.WbFileDO;
import com.liteworkbench.domain.vo.FileVO;
import com.liteworkbench.mapper.WbFileMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 文件空间：元数据进 PostgreSQL，二进制进 MinIO，对象键按用户隔离 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final long MAX_SIZE = 100L * 1024 * 1024;
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WbFileMapper fileMapper;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    /** 分页列表 */
    public PageResult<FileVO> page(String userId, String sessionId, String category,
                                   String keyword, long page, long size) {
        long offset = (page - 1) * size;
        long total = fileMapper.countPage(userId, sessionId, category, keyword);
        if (total <= 0) {
            return PageResult.empty(page, size);
        }
        List<FileVO> list = fileMapper.selectPage(userId, sessionId, category, keyword, offset, size)
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, page, size);
    }

    /** 上传：先写对象后写元数据，失败回滚对象 */
    public FileVO upload(MultipartFile multipart, String userId, String sessionId) {
        if (Objects.isNull(multipart) || multipart.isEmpty()) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "文件不能为空");
        }
        if (multipart.getSize() > MAX_SIZE) {
            throw new BizException(ErrorCodeEnum.FILE_TOO_LARGE);
        }
        String fileId = IdGenerator.generate(IdPrefixConst.FILE);
        String fileName = sanitizeName(multipart.getOriginalFilename());
        String objectKey = StrUtil.format("user/{}/{}/{}/{}/{}",
                userId, "upload", LocalDate.now().format(DAY_FORMAT), fileId, fileName);

        try (InputStream in = multipart.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storageProperties.getBucketName())
                    .object(objectKey)
                    .contentType(StrUtil.blankToDefault(multipart.getContentType(), "application/octet-stream"))
                    .stream(in, multipart.getSize(), -1)
                    .build());
        } catch (Exception e) {
            log.error("[file.upload] 对象写入失败 userId={} file={} {}", userId, fileName, e.getMessage());
            throw new BizException(ErrorCodeEnum.FILE_UPLOAD_FAILED, e.getMessage());
        }

        WbFileDO file = new WbFileDO();
        file.setFileId(fileId);
        file.setUserId(userId);
        file.setSessionId(sessionId);
        file.setFileName(fileName);
        file.setObjectKey(objectKey);
        file.setBucketName(storageProperties.getBucketName());
        file.setFileSize(multipart.getSize());
        file.setContentType(multipart.getContentType());
        file.setCategory("upload");
        file.setCreatedBy(userId);
        try {
            fileMapper.insert(file);
        } catch (Exception e) {
            removeObjectQuietly(objectKey);
            throw new BizException(ErrorCodeEnum.FILE_UPLOAD_FAILED, e.getMessage());
        }
        log.info("[file.upload] userId={} fileId={} size={}", userId, fileId, multipart.getSize());
        return toVO(file);
    }

    /** 预签名下载地址，前端直连 MinIO */
    public String presignedUrl(String userId, String fileId) {
        WbFileDO file = fileMapper.selectByIdAndUser(userId, fileId);
        if (Objects.isNull(file)) {
            throw new BizException(ErrorCodeEnum.FILE_NOT_FOUND, fileId);
        }
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(file.getBucketName())
                    .object(file.getObjectKey())
                    .expiry((int) PRESIGN_TTL.getSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new BizException(ErrorCodeEnum.FILE_NOT_FOUND, e.getMessage());
        }
    }

    /** 删除：先删对象（失败抛 FILE_DELETE_FAILED），再逻辑删元数据 */
    public void delete(String userId, String fileId) {
        WbFileDO file = fileMapper.selectByIdAndUser(userId, fileId);
        if (Objects.isNull(file)) {
            throw new BizException(ErrorCodeEnum.FILE_NOT_FOUND, fileId);
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(file.getBucketName())
                    .object(file.getObjectKey())
                    .build());
        } catch (Exception e) {
            log.error("[file.delete] 对象删除失败 userId={} fileId={} {}", userId, fileId, e.getMessage());
            throw new BizException(ErrorCodeEnum.FILE_DELETE_FAILED, e.getMessage());
        }
        fileMapper.deleteById(userId, fileId);
        log.info("[file.delete] userId={} fileId={}", userId, fileId);
    }

    private void removeObjectQuietly(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // 元数据已失败，对象删除失败仅记日志
        }
    }

    /** 文件名去路径与控制字符，防对象键注入 */
    private String sanitizeName(String name) {
        String clean = StrUtil.blankToDefault(name, "file");
        clean = clean.replaceAll("[\\\\/]", "_").replaceAll("[\\x00-\\x1F]", "");
        return StrUtil.maxLength(clean, 200);
    }

    private FileVO toVO(WbFileDO file) {
        FileVO vo = new FileVO();
        vo.setFileId(file.getFileId());
        vo.setSessionId(file.getSessionId());
        vo.setFileName(file.getFileName());
        vo.setFileSize(file.getFileSize());
        vo.setContentType(file.getContentType());
        vo.setCategory(file.getCategory());
        vo.setCreatedBy(file.getCreatedBy());
        vo.setCreatedAt(Objects.isNull(file.getCreatedAt())
                ? null : LocalDateTimeUtil.toEpochMilli(file.getCreatedAt()));
        return vo;
    }
}
