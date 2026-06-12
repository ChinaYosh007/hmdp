package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long target, Boolean isFollow) {
        if(UserHolder.getUser() == null) return Result.fail("请先登录");
        Long userId = UserHolder.getUser().getId();
        if(isFollow)
        {
            Follow follow = new Follow();

            follow.setUserId(userId);
            follow.setFollowUserId(target);
            stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + userId,target.toString());
            this.save(follow);

        }
        else
        {
            stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOW_KEY + userId, target.toString());
            LambdaQueryWrapper<Follow> wq = new LambdaQueryWrapper();
            wq.eq(Follow::getUserId,userId)
                    .eq(Follow::getFollowUserId,target);

            this.remove(wq);
        }
        return Result.ok("success");

    }

    @Override
    public Result isFollow(Long followId) {
        if(UserHolder.getUser() == null) return Result.fail("请先登录");
        Long userId = UserHolder.getUser().getId();
        Long count = this.lambdaQuery().eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long followId) {
        if(UserHolder.getUser() == null) return Result.fail("请先登录");

        Long userId = UserHolder.getUser().getId();
        String key1 = RedisConstants.FOLLOW_KEY + userId;
        String key2 = RedisConstants.FOLLOW_KEY + followId;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()) return  Result.ok("you are union friend is null!");
        List<Long> list = intersect.stream().map(Long::valueOf).toList();
        List<UserDTO> userDTOS = userService.listByIds(list).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).toList();
        return Result.ok(userDTOS);
    }
}

