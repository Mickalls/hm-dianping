package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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

    @Resource
    private IBlogService blogService;

    @Override
    public Result queryHotBlog(Integer current) {// 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询 blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        // 2. 查询发布blog的用户的头像和昵称
        queryBlogUser(blog);
        // 3. 查询blog是否被当前浏览用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 0. 如果是未登陆用户,不查询
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return;
        }

        // 1. 获取登陆用户
        Long userId = userDTO.getId();

        // 2. 判断是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        // 3. 设置isLike
        blog.setIsLike(score != null);
    }

    /**
     * 点赞功能实现
     * @param id 被点赞的博客的id
     * @return
     */
    public Result likeBlog(Long id) {
        // 1. 获取登陆用户
        Long userId = UserHolder.getUser().getId();

        // 2. 判断是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        // 3. 如果未点赞
        if (score == null) {
            // 3.1 数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 保存用户到Redis的ZSet集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4. 如果已点赞,则取消点赞
            // 4.1 数据库点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询某个博客的点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 查询 top5 的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2. 解析出用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        // 3. 查数据库
        // List<User> users = userService.listByIds(ids);
        String idsStr = StrUtil.join(",", ids);
        List<User> users = userService.query().in("id", ids).last("order by field(id," + idsStr + ")").list();
        List<UserDTO> userDTOs = users
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOs);
    }

    /**
     * 新增博客,并推流给粉丝
     * @param blog
     * @return
     */
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());

        // 2. 保存探店博文
        boolean isSuccess = save(blog); // 这里创建成功后会自动将id赋值回给blog对象
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }

        // 3. 查询笔记作者的所有粉丝
        // select * from tb_follow where follower_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        // 4. 推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 获取粉丝id
            Long userId = follow.getUserId();

            // 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 5. 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查询自己关注的用户所发布的博客
     * @param max 上一次查询的最小id,作为本次分页查询的最大id
     * @param offset 查询id<=max范围并跳过offset个
     * @return 分页查询结果
     */
    public Result queryBlogOfFollower(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;

        // 2. 查询"收件箱" zrevrangebyscore key Max Min WithScores limit offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 3. 解析数据: blogId, score(最小时间戳: minTime), offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int curOffset = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 3.1 获取博客id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));

            // 3.2 获取score
            Long tempTime = tuple.getScore().longValue();
            if (tempTime == minTime) {
                curOffset++;
            } else {
                curOffset = 1;
                minTime = tempTime;
            }
        }

        // 4. 根据id查询blog
        String idsStrs = StrUtil.join(",", ids);
        List<Blog> blogs = blogService.query().in("id", ids).last("order by field(id," + idsStrs + ")").list();
        blogs.forEach(blog -> {
            // 4.1 查询发布blog的用户的头像和昵称
            queryBlogUser(blog);

            // 4.2 查询blog是否被当前浏览用户点赞
            isBlogLiked(blog);
        });

        // 5. 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(curOffset);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    /**
     * 查询发布blog的用户的头像和昵称
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
