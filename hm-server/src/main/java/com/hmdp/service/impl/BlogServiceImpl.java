package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constants.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result getInformation(Long id) {
        Blog blog = getById(id);
        if(blog == null) return Result.fail("user isn't live this..");
        queryBlogUser(blog);
        isBlogLiked(blog);
        return  Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        if (UserHolder.getUser() == null) return;

        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double member = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(member != null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
     return Result.ok(records);
    }

    @Override
    public Result clickLike(Long id) {
        // is alrealy click
        // yes , no
        if (UserHolder.getUser() == null) return Result.ok();
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double member = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(member == null)
        {
            boolean success = this.update().setSql("liked = liked + 1")
                    .eq("id", id)
                    .update();
            if(success)
            {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());

            }

        }
        else
        {
            boolean success = this.update().setSql("liked = liked - 1")
                    .eq("id", id)
                    .update();
            if(success) stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> range = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if(range == null || range.isEmpty()) return Result.ok();
        List<Long> collect = range.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", collect);
        List<UserDTO> users = userService.query().in("id",collect)
                                .last("order by field(id," + join + ")").list().
                                stream().map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                                .toList();

        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        blog.setUserId(userDTO.getId());
        Boolean cur = save(blog);

        if(!cur) return Result.fail("insert error!");
        List<Follow> list = followService.lambdaQuery().eq(Follow::getFollowUserId, blog.getUserId()).list();
        // send message
        list.stream().forEach(follow -> {
            Long userId = follow.getUserId();
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        });

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
       //1.查询用户
        if(UserHolder.getUser() == null) return Result.fail("请先登录");
        Long userId = UserHolder.getUser().getId();
        //2.查看收件箱
        String key = RedisConstants.FEED_KEY + userId;
        //3.解析数据
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if(typedTuples == null || typedTuples.isEmpty()) return Result.ok();

        AtomicReference<Long> lastTime = new AtomicReference<>(0L);
        AtomicReference<Long> cnt = new AtomicReference<>(1L);

        List<Long> ids = typedTuples.stream().map(tuple -> {
            String value = tuple.getValue();
            // 时间戳
            Long curTime = tuple.getScore().longValue();

            if (curTime == lastTime.get()) cnt.getAndSet(cnt.get() + 1);
            else {
                lastTime.set(curTime);
                cnt.set(1L);
            }
            return Long.valueOf(value);
        }).toList();

        String join = StrUtil.join(",", ids);
        List<Blog> blogs = this.query().in("id",ids)
                .last("order by field(id," + join + ")").list().
                stream()
                .map(blog -> {
                    queryBlogUser(blog);
                    isBlogLiked(blog);
                    return blog;
                }).toList();

        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(lastTime.get());
        r.setOffset(Integer.valueOf(cnt.get().toString()));
        return Result.ok(r);

    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
