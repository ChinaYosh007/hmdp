package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.annotation.PhoneMarkAnnotation;
import com.hmdp.constants.UserConstants;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.constants.SessionConstants;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    @PhoneMarkAnnotation(value = "phone")
    public Result sendCode(String phone, HttpSession session) {
        try
        {
            String code = RandomUtil.randomNumbers(6);
            log.warn("phone: {}, code: {}", phone, code);
            session.setAttribute(SessionConstants.SEND_CODE, code);
            return Result.ok();
        }
        catch (Exception e)
        {
            return Result.fail("发送验证码失败");
        }

    }

    @Override
    @PhoneMarkAnnotation
    public Result login(LoginFormDTO loginFormDTO, HttpSession session) {
        //获取session
        Object attribute = session.getAttribute(SessionConstants.SEND_CODE);
        //校验session
        if(!attribute.toString().equals(loginFormDTO.getCode())) {
            return Result.fail("验证码错误");
        }
        //查库
        User user = lambdaQuery().eq(User::getPhone, loginFormDTO.getPhone()).one();
        if(user == null)
        {
          user =  register(loginFormDTO);
        }
        //存入用户信息
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        session.setAttribute(SessionConstants.CURRENT_USER, userDTO);
        return Result.ok();

    }

    public User register(LoginFormDTO loginFormDTO) {
        User user = new User();
        user.setNickName(UserConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setPhone(loginFormDTO.getPhone());
        save(user);
        return user;
    }

}
