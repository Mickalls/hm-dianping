package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 展示点赞量第current高的博客
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询博客详情
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞功能
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    /**
     * 新增博客,并基于推模式推流给粉丝
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 查询自己关注的用户所发布的博客
     * @param max 上一次查询的最小id,作为本次分页查询的最大id
     * @param offset 查询id<=max范围并跳过offset个
     * @return 分页查询结果
     */
    Result queryBlogOfFollower(Long max, Integer offset);
}
