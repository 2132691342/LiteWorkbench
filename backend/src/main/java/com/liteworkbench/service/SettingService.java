package com.liteworkbench.service;

import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.SettingREQ;
import com.liteworkbench.domain.entity.WbSettingDO;
import com.liteworkbench.mapper.WbSettingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 用户设置：键值读写，登录即可用（仅本人） */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingService {

    private final WbSettingMapper settingMapper;

    /** 键值列表 */
    public Map<String, String> list(String userId) {
        Map<String, String> result = new HashMap<>();
        settingMapper.selectByUser(userId)
                .forEach(setting -> result.put(setting.getSettingKey(), setting.getSettingValue()));
        return result;
    }

    /** 单键更新，不存在则写入 */
    public void update(SettingREQ req, String userId) {
        WbSettingDO setting = new WbSettingDO();
        setting.setSettingId(IdGenerator.generate(IdPrefixConst.SETTING));
        setting.setUserId(userId);
        setting.setSettingKey(req.getSettingKey());
        setting.setSettingValue(req.getSettingValue());
        setting.setValueType("string");
        setting.setUpdatedBy(userId);
        settingMapper.upsert(setting);
    }
}
