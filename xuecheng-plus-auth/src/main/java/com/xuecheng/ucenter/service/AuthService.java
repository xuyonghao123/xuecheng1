package com.xuecheng.ucenter.service;

import com.xuecheng.ucenter.model.dto.AuthParamsDto;
import com.xuecheng.ucenter.model.dto.XcUserExt;

public interface AuthService {
    /**
     * @description 认证service
     * @author Xyh
     * @date 2023/07/04
     * @version 1.0
     */
    XcUserExt execute(AuthParamsDto authParamsDto);
}
