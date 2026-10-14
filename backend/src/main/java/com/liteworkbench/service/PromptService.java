package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.DocConst;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.dto.PromptREQ;
import com.liteworkbench.domain.dto.PromptRenderREQ;
import com.liteworkbench.domain.entity.WbPromptDO;
import com.liteworkbench.domain.vo.PromptVO;
import com.liteworkbench.mapper.WbPromptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 提示词模板：CRUD、内置只读保护、变量渲染 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    private final WbPromptMapper promptMapper;

    /** 分页查询：自有模板 + 内置模板 */
    public PageResult<PromptVO> page(PageREQ req, String category) {
        String userId = LoginContext.userId();
        long total = promptMapper.countPage(userId, req, category);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<PromptVO> list = promptMapper.selectPage(userId, req, category, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 详情：自有或内置均可读 */
    public PromptVO detail(String promptId) {
        return toVO(getVisible(promptId));
    }

    public String create(PromptREQ req) {
        String userId = LoginContext.userId();
        requireUniqueTitle(userId, req.getPromptTitle(), null);
        WbPromptDO prompt = new WbPromptDO()
                .setPromptId(IdGenerator.generate(IdPrefixConst.PROMPT))
                .setUserId(userId)
                .setPromptTitle(req.getPromptTitle())
                .setCategory(req.getCategory())
                .setContent(req.getContent())
                .setVariables(req.getVariables())
                .setBuiltin(false)
                .setUseCount(0)
                .setCreatedBy(userId);
        promptMapper.insert(prompt);
        log.info("[prompt.create] userId={} promptId={} title={}", userId, prompt.getPromptId(), prompt.getPromptTitle());
        return prompt.getPromptId();
    }

    /** 更新：内置模板拒绝修改 */
    public void update(PromptREQ req) {
        String userId = LoginContext.userId();
        if (StrUtil.isBlank(req.getPromptId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "promptId 不能为空");
        }
        WbPromptDO existed = getVisible(req.getPromptId());
        if (Boolean.TRUE.equals(existed.getBuiltin())) {
            throw new BizException(ErrorCodeEnum.PROMPT_BUILTIN_READONLY);
        }
        requireUniqueTitle(userId, req.getPromptTitle(), req.getPromptId());
        WbPromptDO prompt = new WbPromptDO()
                .setPromptId(existed.getPromptId())
                .setUserId(userId)
                .setPromptTitle(req.getPromptTitle())
                .setCategory(req.getCategory())
                .setContent(req.getContent())
                .setVariables(req.getVariables())
                .setUpdatedBy(userId);
        promptMapper.update(prompt);
    }

    /** 删除：内置模板拒绝删除 */
    public void delete(String promptId) {
        String userId = LoginContext.userId();
        WbPromptDO existed = getVisible(promptId);
        if (Boolean.TRUE.equals(existed.getBuiltin())) {
            throw new BizException(ErrorCodeEnum.PROMPT_BUILTIN_READONLY);
        }
        promptMapper.deleteById(userId, promptId, userId);
        log.info("[prompt.delete] userId={} promptId={}", userId, promptId);
    }

    /** 复制为自有模板，内置与自有的都可复制 */
    public String copy(String promptId) {
        String userId = LoginContext.userId();
        WbPromptDO source = getVisible(promptId);
        String title = StrUtil.format("{} 副本", source.getPromptTitle());
        requireUniqueTitle(userId, title, null);
        WbPromptDO prompt = new WbPromptDO()
                .setPromptId(IdGenerator.generate(IdPrefixConst.PROMPT))
                .setUserId(userId)
                .setPromptTitle(title)
                .setCategory(source.getCategory())
                .setContent(source.getContent())
                .setVariables(source.getVariables())
                .setBuiltin(false)
                .setUseCount(0)
                .setCreatedBy(userId);
        promptMapper.insert(prompt);
        return prompt.getPromptId();
    }

    /** 变量渲染：传入值优先，其次默认值，都没有即拒绝 */
    public String render(PromptRenderREQ req) {
        String userId = LoginContext.userId();
        WbPromptDO prompt = getVisible(req.getPromptId());
        Map<String, String> values = Objects.isNull(req.getVariables()) ? Map.of() : req.getVariables();
        String text = prompt.getContent();
        for (PromptVO.PromptVariable variable : parseVariables(prompt.getVariables())) {
            String value = values.get(variable.getName());
            if (StrUtil.isBlank(value)) {
                value = variable.getDefaultValue();
            }
            if (StrUtil.isBlank(value)) {
                throw new BizException(ErrorCodeEnum.PROMPT_VARIABLE_MISSING, variable.getName());
            }
            text = text.replace("{{" + variable.getName() + "}}", value);
        }
        promptMapper.incrementUseCount(prompt.getPromptId());
        log.info("[prompt.render] userId={} promptId={} length={}", userId, prompt.getPromptId(), text.length());
        return text;
    }

    /** 可见性：自有模板或内置模板，其余一律 404 */
    private WbPromptDO getVisible(String promptId) {
        WbPromptDO prompt = promptMapper.selectById(promptId);
        if (Objects.isNull(prompt)) {
            throw new BizException(ErrorCodeEnum.PROMPT_NOT_FOUND, promptId);
        }
        String userId = LoginContext.userId();
        boolean visible = Boolean.TRUE.equals(prompt.getBuiltin()) || Objects.equals(userId, prompt.getUserId());
        if (!visible) {
            throw new BizException(ErrorCodeEnum.PROMPT_NOT_FOUND, promptId);
        }
        return prompt;
    }

    private void requireUniqueTitle(String userId, String promptTitle, String excludeId) {
        if (promptMapper.countByTitle(userId, promptTitle, excludeId) > 0) {
            throw new BizException(ErrorCodeEnum.PROMPT_TITLE_CONFLICT, promptTitle);
        }
    }

    /** 变量定义解析，格式非法视为无变量 */
    private List<PromptVO.PromptVariable> parseVariables(String raw) {
        if (StrUtil.isBlank(raw) || !JSONUtil.isJsonArray(raw)) {
            return List.of();
        }
        try {
            return JSONUtil.toList(JSONUtil.parseArray(raw), PromptVO.PromptVariable.class);
        } catch (Exception e) {
            log.warn("[prompt.variables] 变量定义解析失败 {}", e.getMessage());
            return List.of();
        }
    }

    private PromptVO toVO(WbPromptDO prompt) {
        PromptVO vo = new PromptVO();
        vo.setPromptId(prompt.getPromptId());
        vo.setUserId(prompt.getUserId());
        vo.setPromptTitle(prompt.getPromptTitle());
        vo.setCategory(prompt.getCategory());
        vo.setContent(prompt.getContent());
        vo.setVariables(parseVariables(prompt.getVariables()));
        vo.setBuiltin(prompt.getBuiltin());
        vo.setUseCount(prompt.getUseCount());
        vo.setCreatedAt(TimeUtil.toMillis(prompt.getCreatedAt()));
        vo.setUpdatedAt(TimeUtil.toMillis(prompt.getUpdatedAt()));
        return vo;
    }

    /** 内置模板种子：启动时补齐，已存在则跳过 */
    public void initBuiltin(List<BuiltinSeed> seeds) {
        for (BuiltinSeed seed : seeds) {
            if (Objects.nonNull(promptMapper.selectById(seed.promptId()))) {
                continue;
            }
            WbPromptDO prompt = new WbPromptDO()
                    .setPromptId(seed.promptId())
                    .setUserId(DocConst.BUILTIN_USER_ID)
                    .setPromptTitle(seed.title())
                    .setCategory(seed.category())
                    .setContent(seed.content())
                    .setVariables(seed.variables())
                    .setBuiltin(true)
                    .setUseCount(0)
                    .setCreatedBy("system");
            promptMapper.insert(prompt);
        }
    }

    /** 内置模板种子结构 */
    public record BuiltinSeed(String promptId, String title, String category, String content, String variables) {
    }
}
