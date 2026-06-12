package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;
    private   HashOperations<String, String, Object> ops;

    /**
     * @PostConstruct 会在依赖注入完成后、Bean 投入使用前执行，此时 redisTemplate 已经有值了。
     */
    @PostConstruct
    public void init()
    {
        ops = stringRedisTemplate.opsForHash();
    }

    @Override
    @PhoneMarkAnnotation(value = "phone")
    public Result sendCode(String phone, HttpSession session) {
        try
        {
            String code = RandomUtil.randomNumbers(6);
            // 每个手机号独立 key，避免共用 key 时 TTL 互相干扰
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.LOGIN_CODE_KEY + phone, code,
                    RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
            log.warn("code: {}",code);
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

        String attribute = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginFormDTO.getPhone());
        if(attribute == null || !attribute.equals(loginFormDTO.getCode())) {
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
        Map<String, Object> map = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                                                                            .setIgnoreNullValue(true)
                                                                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String token = RandomUtil.randomString(32);
        String tokenKey =  RedisConstants.LOGIN_USER_KEY + token;
        ops.putAll(tokenKey,map);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);

    }

    @Override
    public Result logout() {
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public List<User> likeByIds(List<Long> collect) {
        return collect.stream().map(id ->
        {
            return this.getById(id);
        }).collect(Collectors.toList());
    }

    public User register(LoginFormDTO loginFormDTO) {
        User user = new User();
        user.setNickName(UserConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setPhone(loginFormDTO.getPhone());
        save(user);
        return user;
    }

}
