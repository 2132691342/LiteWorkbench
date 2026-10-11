package com.liteworkbench.service;

import cn.hutool.core.compress.ZipReader;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.XmlUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.domain.constant.KbConst;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 知识库文本提取：MinIO 拉原件，按扩展名分发文本直读 / docx 解包 / pdf 解析 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocTextExtractor {

    private final MinioClient minioClient;

    /** 提取纯文本；仅支持 KbConst 白名单格式，超上限截断 */
    public String extract(String bucketName, String objectKey, String fileName) {
        String extension = StrUtil.subAfter(StrUtil.nullToDefault(fileName, ""), '.', true)
                .toLowerCase();
        boolean textFormat = KbConst.TEXT_EXTENSIONS.contains(extension);
        boolean parserFormat = KbConst.PARSER_EXTENSIONS.contains(extension);
        if (!textFormat && !parserFormat) {
            throw new BizException(ErrorCodeEnum.KB_DOC_TYPE_UNSUPPORTED, fileName);
        }
        byte[] bytes = loadObject(bucketName, objectKey);
        try {
            String content = switch (extension) {
                case "docx" -> readDocx(bytes);
                case "pdf" -> readPdf(bytes);
                default -> readText(bytes);
            };
            if (content.length() > KbConst.MAX_DOC_CHARS) {
                log.info("[kb.extract] 正文超长截断 file={} {} -> {}", fileName, content.length(), KbConst.MAX_DOC_CHARS);
                content = content.substring(0, KbConst.MAX_DOC_CHARS);
            }
            return content;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[kb.extract] 解析失败 file={} {}", fileName, e.getMessage());
            throw new BizException(ErrorCodeEnum.KB_DOC_PARSE_FAILED, e.getMessage());
        }
    }

    /** 拉取对象字节，失败统一转解析失败 */
    private byte[] loadObject(String bucketName, String objectKey) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName).object(objectKey).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("[kb.extract] 对象读取失败 {} {}", objectKey, e.getMessage());
            throw new BizException(ErrorCodeEnum.KB_DOC_PARSE_FAILED, e.getMessage());
        }
    }

    /** UTF-8 直读，剥 BOM */
    private String readText(byte[] bytes) {
        return StrUtil.str(bytes, StandardCharsets.UTF_8).strip().replace("\uFEFF", "");
    }

    /** docx 解包取 word/document.xml，按段落聚合文本 */
    private String readDocx(byte[] bytes) throws Exception {
        byte[] xml;
        try (ZipReader reader = ZipReader.of(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            try (InputStream entry = reader.get("word/document.xml")) {
                xml = Objects.isNull(entry) ? null : IoUtil.readBytes(entry);
            }
        }
        if (Objects.isNull(xml)) {
            throw new BizException(ErrorCodeEnum.KB_DOC_PARSE_FAILED, "docx 缺少 word/document.xml");
        }
        Document document = XmlUtil.readXML(new ByteArrayInputStream(xml));
        StringBuilder content = new StringBuilder();
        NodeList paragraphs = document.getElementsByTagName("w:p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            String line = collectText(paragraphs.item(i)).strip();
            if (StrUtil.isNotBlank(line)) {
                content.append(line).append('\n');
            }
        }
        String result = content.toString().strip();
        if (StrUtil.isBlank(result)) {
            throw new BizException(ErrorCodeEnum.KB_DOC_PARSE_FAILED, "docx 未提取到文本");
        }
        return result;
    }

    /** 深度聚合节点文本，w:t 之间不加空格保持原排版 */
    private String collectText(Node node) {
        StringBuilder builder = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if ("w:t".equals(child.getNodeName()) || "t".equals(child.getNodeName())) {
                builder.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                builder.append(collectText(child));
            }
        }
        return builder.toString();
    }

    /** pdf 走 pdfbox 文本剥离 */
    private String readPdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            String text = new PDFTextStripper().getText(document);
            if (StrUtil.isBlank(text)) {
                throw new BizException(ErrorCodeEnum.KB_DOC_PARSE_FAILED, "pdf 未提取到文本，可能是扫描件");
            }
            return text.strip();
        }
    }
}
