package com.allsparkstudio.zaixiyou.service.impl;

import com.allsparkstudio.zaixiyou.dao.*;
import com.allsparkstudio.zaixiyou.enums.*;
import com.allsparkstudio.zaixiyou.pojo.form.AddArticleForm;
import com.allsparkstudio.zaixiyou.pojo.form.AddCircleArticleForm;
import com.allsparkstudio.zaixiyou.pojo.form.AddCirclePostForm;
import com.allsparkstudio.zaixiyou.pojo.form.AddPostForm;
import com.allsparkstudio.zaixiyou.pojo.po.*;
import com.allsparkstudio.zaixiyou.pojo.vo.PostVO;
import com.allsparkstudio.zaixiyou.pojo.vo.ResponseVO;
import com.allsparkstudio.zaixiyou.service.PostService;
import com.allsparkstudio.zaixiyou.util.JWTUtils;
import com.allsparkstudio.zaixiyou.util.UserDailyStatisticsUtils;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;


/**
 * @author 陈帅
 * @date 2020/8/20
 */
@Service
@Slf4j
public class PostServiceImpl implements PostService {

    private static SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private PostCategoryMapper postCategoryMapper;

    @Autowired
    private UserCircleMapper userCircleMapper;

    @Autowired
    private PostCircleMapper postCircleMapper;

    @Autowired
    private UserPostLikeMapper userPostLikeMapper;

    @Autowired
    private UserPostFavoriteMapper userPostFavoriteMapper;

    @Autowired
    private UserPostCoinMapper userPostCoinMapper;

    @Autowired
    private UserCommentLikeMapper userCommentLikeMapper;

    @Autowired
    private UserCommentCoinMapper userCommentCoinMapper;

    @Autowired
    JWTUtils jwtUtils;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    UserDailyStatisticsUtils userDailyStatisticsUtils;

    @Override
    public ResponseVO<PageInfo> listAll(Integer categoryId, Integer userId, Integer circleId,
                                        Integer type, UserContentStateEnum stateEnum,
                                        String token, Integer pageNum, Integer pageSize,
                                        Integer sortedBy) {
        Integer myId = null;
        boolean login = false;
        if (!StringUtils.isEmpty(token)) {
            if (jwtUtils.validateToken(token)) {
                myId = jwtUtils.getIdFromToken(token);
                login = true;
            }
        }
        List<Post> postList;
        if (categoryId != null) {
            // 根据分类来列出帖子
            if (SortTypeEnum.HEAT.getCode().equals(sortedBy)) {
                PageHelper.startPage(pageNum, pageSize);
                postList = postMapper.selectPostsByCategoryIdSortedByHeat(categoryId);
            } else {
                PageHelper.startPage(pageNum, pageSize);
                postList = postMapper.selectPostsByCategoryIdSortedByTime(categoryId);
            }
        } else if (userId != null && type != null) {
            // 根据作者和帖子类型来列出帖子
            PageHelper.startPage(pageNum, pageSize);
            postList = postMapper.selectByUserIdAndType(userId, type);
        } else if (stateEnum == UserContentStateEnum.FAVORITE && type != null) {
            // 根据用户点赞/投币/收藏来列出帖子
            if (!login) {
                return ResponseVO.error(ResponseEnum.NEED_LOGIN);
            }
            PageHelper.startPage(pageNum, pageSize);
            postList = postMapper.selectFavoritesPostsByUserIdAndType(myId, type);
        } else if (circleId != null) {
            // 根据圈子来列出帖子
            if (SortTypeEnum.HEAT.getCode().equals(sortedBy)) {
                PageHelper.startPage(pageNum, pageSize);
                postList = postMapper.selectPostsByCircleIdSortedByHeat(circleId);
            } else {
                PageHelper.startPage(pageNum, pageSize);
                postList = postMapper.selectPostsByCircleIdSortedByTime(circleId);
            }
        } else {
            // 列出全部帖子
            if (SortTypeEnum.HEAT.getCode().equals(sortedBy)) {
                PageHelper.startPage(pageNum, pageSize);
                postList = postMapper.selectAllByHeat();
            } else {
                PageHelper.startPage(pageNum, pageSize);
                postList = postMapper.selectAllByTime();
            }
        }
        List<PostVO> postVOList = new ArrayList<>();
        for (Post post : postList) {
            Integer postType = post.getType();
            PostVO postVO = new PostVO();
            postVO.setType(postType);
            if (PostTypeEnum.POST.getCode().equals(postType)) {
                if (post.getPostMediaUrls() != null && !StringUtils.isEmpty(post.getPostMediaUrls())) {
                    List<String> mediaUrlList;
                    String[] mediaUrl = post.getPostMediaUrls().split(";");
                    mediaUrlList = Arrays.asList(mediaUrl);
                    postVO.setMediaUrls(mediaUrlList);
                }
            }
            if (PostTypeEnum.ARTICLE.getCode().equals(postType)) {
                postVO.setTitle(post.getArticleTitle());
                postVO.setPureText(post.getArticleText());
                postVO.setCover(post.getArticleCover());
            }
            postVO.setPostId(post.getId());
            User author = userMapper.selectByPrimaryKey(post.getAuthorId());
            postVO.setAuthorId(author.getId());
            postVO.setAuthorName(author.getNickname());
            postVO.setAuthorAvatar(author.getAvatarUrl());
            postVO.setAuthorDescription(author.getDescription());
            postVO.setAuthorLevel(author.getLevel());
            postVO.setBody(post.getBody());
            simpleDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            postVO.setCreateTime(simpleDateFormat.format(post.getCreateTime()));
            Integer likesNum = userPostLikeMapper.countByPostId(post.getId());
            Integer favoritesNum = userPostFavoriteMapper.countByPostId(post.getId());
            Integer coinsNum = userPostCoinMapper.countByPostId(post.getId());
            Integer commentsNum = commentMapper.countCommentsByPostId(post.getId());
            postVO.setLikeNum(likesNum);
            postVO.setFavoriteNum(favoritesNum);
            postVO.setCoinsNum(coinsNum);
            postVO.setCommentNum(commentsNum);
            boolean liked = false;
            boolean coined = false;
            boolean favorited = false;
            if (login) {
                UserPostLike userPostLike = userPostLikeMapper.selectByUserIdAndPostId(myId, post.getId());
                UserPostFavorite userPostFavorite = userPostFavoriteMapper.selectByUserIdAndPostId(myId, post.getId());
                UserPostCoin userPostCoin = userPostCoinMapper.selectByUserIdAndPostId(myId, post.getId());
                if (userPostLike != null && userPostLike.getState().equals(1)) {
                    liked = true;
                }
                if (userPostFavorite != null && userPostFavorite.getState().equals(1)) {
                    favorited = true;
                }
                if (userPostCoin != null && userPostCoin.getState().equals(1)) {
                    coined = true;
                }
            }
            postVO.setLiked(liked);
            postVO.setCoined(coined);
            postVO.setFavorited(favorited);

            // 设置@的用户
            if (post.getAtIds() != null && !StringUtils.isEmpty(post.getAtIds())) {
                List<Map<String, Object>> atMapList = new ArrayList<>();
                String[] atIds = post.getAtIds().split(";");
                for (String atId : atIds) {
                    User user = userMapper.selectByPrimaryKey(Integer.parseInt(atId));
                    Map<String, Object> atMap = new HashMap<>(2);
                    atMap.put("id", user.getId());
                    atMap.put("nickName", user.getNickname());
                    atMapList.add(atMap);
                }
                postVO.setAts(atMapList);
            }

            // 设置自定义标签
            if (post.getTags() != null && !StringUtils.isEmpty(post.getTags())) {
                List<String> tagList;
                String[] tags = post.getTags().split(";");
                tagList = Arrays.asList(tags);
                // 历史遗留原因导致要用map存
                List<Map<String, String>> tagMapList = new ArrayList<>();
                for (String tag : tagList) {
                    Map<String, String> tagMap = new HashMap<>(2);
                    tagMap.put("name", tag);
                    tagMapList.add(tagMap);
                }
                postVO.setTags(tagMapList);
            }
            postVOList.add(postVO);
        }

        PageInfo pageInfo = new PageInfo<>(postList);
        pageInfo.setList(postVOList);
        return ResponseVO.success(pageInfo);
    }

    @Override
    public ResponseVO like(Integer postId, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        Post post = postMapper.selectByPrimaryKey(postId);
        if (post == null) {
            log.error("请求的帖子不存在, postId:[{}]", postId);
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "帖子不存在");
        }
        UserPostLike userPostLike = userPostLikeMapper.selectByUserIdAndPostId(userId, postId);
        if (userPostLike == null) {
            userPostLike = new UserPostLike();
            userPostLike.setUserId(userId);
            userPostLike.setPostId(postId);
            userPostLike.setState(1);
            int result = userPostLikeMapper.insertSelective(userPostLike);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
            // MQ增加经验值
            rabbitTemplate.convertAndSend("dailyStatisticsExchange", "getLike", post.getAuthorId());
            // MQ通知作者内容被点赞
            EventRemind remind = new EventRemind();
            remind.setAction(RemindActionEnum.LIKE_POST.getCode());
            if (PostTypeEnum.POST.getCode().equals(post.getType())) {
                StringBuilder sourceContentBuilder = new StringBuilder();
                if (post.getBody() != null) {
                    sourceContentBuilder.append(post.getBody().length() > 20 ? post.getBody().substring(0, 20) + "..." : post.getBody());
                }
                if (post.getPostMediaUrls() != null) {
                    for (int i = 0; i < post.getPostMediaUrls().split(";").length; i++) {
                        sourceContentBuilder.append("[图片]");
                    }
                }
                remind.setSourceContent(sourceContentBuilder.toString());
            } else {
                remind.setSourceContent(post.getArticleTitle());
            }
            remind.setSourceId(postId);
            remind.setSenderId(userId);
            remind.setReceiveId(post.getAuthorId());
            rabbitTemplate.convertAndSend("eventRemind", remind);
        } else {
            userPostLike.setState(1);
            int result = userPostLikeMapper.updateByPrimaryKeySelective(userPostLike);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        rabbitTemplate.convertAndSend("updateHeat", "post", postId);
        return ResponseVO.success();
    }

    @Override
    public ResponseVO dislike(Integer postId, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        Post post = postMapper.selectByPrimaryKey(postId);
        if (post == null) {
            log.error("请求的帖子不存在, postId:[{}]", postId);
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "帖子不存在");
        }
        UserPostLike userPostLike = userPostLikeMapper.selectByUserIdAndPostId(userId, postId);
        if (userPostLike == null) {
            userPostLike = new UserPostLike();
            userPostLike.setUserId(userId);
            userPostLike.setPostId(postId);
            userPostLike.setState(0);
            int result = userPostLikeMapper.insertSelective(userPostLike);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        } else {
            userPostLike.setState(0);
            int result = userPostLikeMapper.updateByPrimaryKeySelective(userPostLike);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        return ResponseVO.success();
    }

    @Override
    public ResponseVO favorite(Integer postId, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        Post post = postMapper.selectByPrimaryKey(postId);
        if (post == null) {
            log.error("请求的帖子不存在, postId:[{}]", postId);
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "帖子不存在");
        }
        UserPostFavorite userPostFavorite = userPostFavoriteMapper.selectByUserIdAndPostId(userId, postId);
        if (userPostFavorite == null) {
            userPostFavorite = new UserPostFavorite();
            userPostFavorite.setUserId(userId);
            userPostFavorite.setPostId(postId);
            userPostFavorite.setState(1);
            int result = userPostFavoriteMapper.insertSelective(userPostFavorite);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
            // MQ增加经验值
            rabbitTemplate.convertAndSend("dailyStatisticsExchange", "getFavorite", post.getAuthorId());
            // MQ通知作者内容被收藏
            EventRemind remind = new EventRemind();
            remind.setAction(RemindActionEnum.FAVORITE_POST.getCode());
            if (PostTypeEnum.POST.getCode().equals(post.getType())) {
                StringBuilder sourceContentBuilder = new StringBuilder();
                if (post.getBody() != null) {
                    sourceContentBuilder.append(post.getBody().length() > 20 ? post.getBody().substring(0, 20) + "..." : post.getBody());
                }
                if (post.getPostMediaUrls() != null) {
                    for (int i = 0; i < post.getPostMediaUrls().split(";").length; i++) {
                        sourceContentBuilder.append("[图片]");
                    }
                }
                remind.setSourceContent(sourceContentBuilder.toString());
            } else {
                remind.setSourceContent(post.getArticleTitle());
            }
            remind.setSourceId(postId);
            remind.setSenderId(userId);
            remind.setReceiveId(post.getAuthorId());
            rabbitTemplate.convertAndSend("eventRemind", remind);
        } else {
            userPostFavorite.setState(1);
            int result = userPostFavoriteMapper.updateByPrimaryKeySelective(userPostFavorite);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        rabbitTemplate.convertAndSend("updateHeat", "post", postId);
        return ResponseVO.success();
    }

    @Override
    public ResponseVO disFavorite(Integer postId, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        Post post = postMapper.selectByPrimaryKey(postId);
        if (post == null) {
            log.error("请求的帖子不存在, postId:[{}]", postId);
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "帖子不存在");
        }
        UserPostFavorite userPostFavorite = userPostFavoriteMapper.selectByUserIdAndPostId(userId, postId);
        if (userPostFavorite == null) {
            userPostFavorite = new UserPostFavorite();
            userPostFavorite.setUserId(userId);
            userPostFavorite.setPostId(postId);
            userPostFavorite.setState(0);
            int result = userPostFavoriteMapper.insertSelective(userPostFavorite);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        } else {
            userPostFavorite.setState(0);
            int result = userPostFavoriteMapper.updateByPrimaryKeySelective(userPostFavorite);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        return ResponseVO.success();
    }

    @Override
    public ResponseVO coin(Integer postId, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        Post post = postMapper.selectByPrimaryKey(postId);
        if (post == null) {
            log.error("请求的帖子不存在, postId:[{}]", postId);
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "帖子不存在");
        }
        User user = userMapper.selectByPrimaryKey(userId);
        if (userId.equals(post.getAuthorId())) {
            return ResponseVO.error(ResponseEnum.HAVE_NOT_PERMISSION, "不能给自己投币哦");
        }
        if (user.getInsertableCoins() <= 0) {
            return ResponseVO.error(ResponseEnum.COINS_NOT_ENOUGH);
        }
        user.setInsertableCoins(user.getInsertableCoins() - 1);
        User author = userMapper.selectByPrimaryKey(post.getAuthorId());
        author.setExchangeableCoins(author.getExchangeableCoins());
        userMapper.updateInsertableCoins(user);
        userMapper.updateExchangeableCoins(user);

        UserPostCoin userPostCoin = userPostCoinMapper.selectByUserIdAndPostId(userId, postId);
        if (userPostCoin == null) {
            userPostCoin = new UserPostCoin();
            userPostCoin.setUserId(userId);
            userPostCoin.setPostId(postId);
            userPostCoin.setState(1);
            int result = userPostCoinMapper.insertSelective(userPostCoin);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
            // MQ增加经验值
            rabbitTemplate.convertAndSend("dailyStatisticsExchange", "insertCoin", userId);
            rabbitTemplate.convertAndSend("dailyStatisticsExchange", "getCoin", post.getAuthorId());

            // MQ通知作者内容被投币
            EventRemind remind = new EventRemind();
            remind.setAction(RemindActionEnum.COIN_POST.getCode());
            if (PostTypeEnum.POST.getCode().equals(post.getType())) {
                StringBuilder sourceContentBuilder = new StringBuilder();
                if (post.getBody() != null) {
                    sourceContentBuilder.append(post.getBody().length() > 20 ? post.getBody().substring(0, 20) + "..." : post.getBody());
                }
                if (post.getPostMediaUrls() != null) {
                    for (int i = 0; i < post.getPostMediaUrls().split(";").length; i++) {
                        sourceContentBuilder.append("[图片]");
                    }
                }
                remind.setSourceContent(sourceContentBuilder.toString());
            } else {
                remind.setSourceContent(post.getArticleTitle());
            }
            remind.setSourceId(postId);
            remind.setSenderId(userId);
            remind.setReceiveId(post.getAuthorId());
            rabbitTemplate.convertAndSend("eventRemind", remind);
        } else {
            userPostCoin.setState(1);
            int result = userPostCoinMapper.updateByPrimaryKeySelective(userPostCoin);
            if (result != 1) {
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        rabbitTemplate.convertAndSend("updateHeat", "post", postId);
        return ResponseVO.success();
    }

    @Override
    public ResponseVO<Map<String, Integer>> addPost(AddPostForm addPostForm, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        User user = userMapper.selectByPrimaryKey(userId);
        if (UserStateEnum.MUTE.getCode().equals(user.getState())) {
            return ResponseVO.error(ResponseEnum.MUTE);
        }
        if (userDailyStatisticsUtils.isAddPostLimited(userId)) {
            return ResponseVO.error(ResponseEnum.REACH_PUBLISH_LIMIT);
        }
        if ((addPostForm.getBody() == null || StringUtils.isEmpty(addPostForm.getBody())) &&
                addPostForm.getMediaUrls() == null || StringUtils.isEmpty(addPostForm.getMediaUrls())) {
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "请填写内容");
        }
        Post post = new Post();
        post.setType(PostTypeEnum.POST.getCode());
        post.setAuthorId(userId);
        post.setBody(addPostForm.getBody());
        // 设置帖子的图片或视频url列表
        if (addPostForm.getMediaUrls() != null && addPostForm.getMediaUrls().size() != 0) {
            StringBuilder stringBuilder = new StringBuilder();
            for (String mediaUrl : addPostForm.getMediaUrls()) {
                stringBuilder.append(mediaUrl);
                stringBuilder.append(";");
            }
            String postMediaUrls = stringBuilder.substring(0, stringBuilder.length() - 1);
            post.setPostMediaUrls(postMediaUrls);
        }
        // 设置文章@的人的id
        if (addPostForm.getAtIds() != null && addPostForm.getAtIds().size() != 0) {
            StringBuilder stringBuilder1 = new StringBuilder();
            for (Integer atId : addPostForm.getAtIds()) {
                stringBuilder1.append(atId);
                stringBuilder1.append(";");
            }
            String atIds = stringBuilder1.substring(0, stringBuilder1.length() - 1);
            post.setAtIds(atIds);
        }
        // 设置帖子的自定义标签
        if (addPostForm.getCustomTags() != null && addPostForm.getCustomTags().size() != 0) {
            StringBuilder stringBuilder1 = new StringBuilder();
            for (String tag : addPostForm.getCustomTags()) {
                stringBuilder1.append(tag);
                stringBuilder1.append(";");
            }
            String tags = stringBuilder1.substring(0, stringBuilder1.length() - 1);
            post.setTags(tags);
        }
        int result = postMapper.insertSelective(post);
        if (result != 1) {
            log.error("新建文章时，数据库表'post'插入失败");
            return ResponseVO.error(ResponseEnum.ERROR);
        }
        // MQ更新用户当日发帖子数量，更新用户经验
        rabbitTemplate.convertAndSend("dailyStatisticsExchange", "addPost", userId);
        // 通过MQ同步数据到ES
        rabbitTemplate.convertAndSend("MySQL2ESPostExchange", "add", post);
        // 通过MQ通知被@的用户
        for (Integer atId : addPostForm.getAtIds()) {
            EventRemind remind = new EventRemind();
            remind.setSenderId(userId);
            StringBuilder sourceContentBuilder = new StringBuilder();
            if (post.getBody() != null) {
                sourceContentBuilder.append(post.getBody().length() > 20 ? post.getBody().substring(0, 20) + "..." : post.getBody());
            }
            if (post.getPostMediaUrls() != null) {
                for (int i = 0; i < post.getPostMediaUrls().split(";").length; i++) {
                    sourceContentBuilder.append("[图片]");
                }
            }
            remind.setSourceContent(sourceContentBuilder.toString());
            remind.setReceiveId(atId);
            remind.setSourceId(post.getId());
            remind.setAction(RemindActionEnum.AT.getCode());
            rabbitTemplate.convertAndSend("eventRemind", remind);
        }
        // 绑定主标签（分类）和帖子
        for (Integer mainTagId : addPostForm.getMainTagIds()) {
            PostCategory postCategory = new PostCategory();
            postCategory.setPostId(post.getId());
            postCategory.setCategoryId(mainTagId);
            int result1 = postCategoryMapper.insertSelective(postCategory);
            if (result1 != 1) {
                log.error("新建文章时，数据库表'post_category'插入失败");
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        // 设置文章同步到哪个圈子
        for (Integer circleId : addPostForm.getCircleIds()) {
            PostCircle postCircle = new PostCircle();
            postCircle.setPostId(post.getId());
            postCircle.setCircleId(circleId);
            int result2 = postCircleMapper.insertSelective(postCircle);
            if (result2 != 1) {
                log.error("新建文章时，数据库表'post_circle'插入失败");
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        Map<String, Integer> map = new HashMap<>(1);
        map.put("postId", post.getId());
        return ResponseVO.success(map);
    }

    @Override
    public ResponseVO<Map<String, Integer>> addArticle(AddArticleForm addArticleForm, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        User user = userMapper.selectByPrimaryKey(userId);
        if (UserStateEnum.MUTE.getCode().equals(user.getState())) {
            return ResponseVO.error(ResponseEnum.MUTE);
        }
        if (userDailyStatisticsUtils.isAddPostLimited(userId)) {
            return ResponseVO.error(ResponseEnum.REACH_PUBLISH_LIMIT);
        }
        if (addArticleForm.getTitle() == null || StringUtils.isEmpty(addArticleForm.getTitle())) {
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "请输入标题");
        }
        Post article = new Post();
        article.setType(PostTypeEnum.ARTICLE.getCode());
        article.setArticleCover(addArticleForm.getCover());
        article.setArticleText(addArticleForm.getPureText());
        article.setAuthorId(userId);
        article.setArticleTitle(addArticleForm.getTitle());
        article.setBody(addArticleForm.getBody());
        // 设置文章@的人的id
        if (addArticleForm.getAtIds() != null && addArticleForm.getAtIds().size() != 0) {
            StringBuilder stringBuilder = new StringBuilder();
            for (Integer atId : addArticleForm.getAtIds()) {
                stringBuilder.append(atId);
                stringBuilder.append(";");
            }
            String atIds = stringBuilder.substring(0, stringBuilder.length() - 1);
            article.setAtIds(atIds);
        }
        // 设置文章的自定义标签
        if (addArticleForm.getCustomTags() != null && addArticleForm.getCustomTags().size() != 0) {
            StringBuilder stringBuilder1 = new StringBuilder();
            for (String tag : addArticleForm.getCustomTags()) {
                stringBuilder1.append(tag);
                stringBuilder1.append(";");
            }
            String tags = stringBuilder1.substring(0, stringBuilder1.length() - 1);
            article.setTags(tags);
        }
        int result = postMapper.insertSelective(article);
        if (result != 1) {
            log.error("新建文章时，数据库表'post'插入失败");
            return ResponseVO.error(ResponseEnum.ERROR);
        }
        // MQ更新用户当日发帖子数量，更新用户经验
        rabbitTemplate.convertAndSend("dailyStatisticsExchange", "addPost", userId);
        // 通过MQ同步数据到ES
        rabbitTemplate.convertAndSend("MySQL2ESPostExchange", "add", article);
        // MQ通知被@的用户
        for (Integer atId : addArticleForm.getAtIds()) {
            EventRemind remind = new EventRemind();
            remind.setSourceContent(article.getArticleTitle());
            remind.setAction(RemindActionEnum.AT.getCode());
            remind.setSenderId(userId);
            remind.setReceiveId(atId);
            remind.setSourceId(article.getId());
            rabbitTemplate.convertAndSend("eventRemind", remind);
        }

        // 绑定主标签（分类）和帖子
        for (Integer mainTagId : addArticleForm.getMainTagIds()) {
            PostCategory postCategory = new PostCategory();
            postCategory.setPostId(article.getId());
            postCategory.setCategoryId(mainTagId);
            int result1 = postCategoryMapper.insertSelective(postCategory);
            if (result1 != 1) {
                log.error("新建文章时，数据库表'post_category'插入失败");
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        // 设置文章同步到哪个圈子
        for (Integer circleId : addArticleForm.getCircleIds()) {
            PostCircle postCircle = new PostCircle();
            postCircle.setPostId(article.getId());
            postCircle.setCircleId(circleId);
            int result2 = postCircleMapper.insertSelective(postCircle);
            if (result2 != 1) {
                log.error("新建文章时，数据库表'post_circle'插入失败");
                return ResponseVO.error(ResponseEnum.ERROR);
            }
        }
        Map<String, Integer> map = new HashMap<>(1);
        map.put("postId", article.getId());
        return ResponseVO.success(map);
    }

    @Override
    public ResponseVO<Map<String, Integer>> addPostInCircle(Integer circleId, AddCirclePostForm form, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        User user = userMapper.selectByPrimaryKey(userId);
        if (UserStateEnum.MUTE.getCode().equals(user.getState())) {
            return ResponseVO.error(ResponseEnum.MUTE);
        }
        if (userDailyStatisticsUtils.isAddPostLimited(userId)) {
            return ResponseVO.error(ResponseEnum.REACH_PUBLISH_LIMIT);
        }
        Integer role = userCircleMapper.selectRoleOrNull(userId, circleId);
        if (role == null || role < UserCircleRoleEnum.FOLLOW.getCode()) {
            return ResponseVO.error(ResponseEnum.HAVE_NOT_PERMISSION);
        }
        Post post = new Post();
        post.setType(PostTypeEnum.POST.getCode());
        post.setAuthorId(userId);
        post.setBody(form.getBody());
        post.setVisibleOutsideCircle(false);
        if (form.getMediaUrls() != null && form.getMediaUrls().size() != 0) {
            StringBuilder stringBuilder = new StringBuilder();
            for (String mediaUrl : form.getMediaUrls()) {
                stringBuilder.append(mediaUrl);
                stringBuilder.append(";");
            }
            String postMediaUrls = stringBuilder.substring(0, stringBuilder.length() - 1);
            post.setPostMediaUrls(postMediaUrls);
        }
        int result = postMapper.insertSelective(post);
        if (result != 1) {
            log.error("圈子内发布帖子时出现错误,数据库表'post'插入失败");
            return ResponseVO.error(ResponseEnum.ERROR);
        }
        // MQ更新用户当日发帖子数量，更新用户经验
        rabbitTemplate.convertAndSend("dailyStatisticsExchange", "addPost", userId);
        // 通过MQ同步数据到ES
        rabbitTemplate.convertAndSend("MySQL2ESPostExchange", "add", post);
        PostCircle postCircle = new PostCircle();
        postCircle.setCircleId(circleId);
        postCircle.setPostId(post.getId());
        int result1 = postCircleMapper.insertSelective(postCircle);
        if (result1 != 1) {
            log.error("圈子内发布帖子时出现错误,数据库表'post_circle'插入失败");
            return ResponseVO.error(ResponseEnum.ERROR);
        }
        rabbitTemplate.convertAndSend("updateHeat", "circle", circleId);
        Map<String, Integer> map = new HashMap<>(1);
        map.put("postId", post.getId());

        return ResponseVO.success(map);
    }

    @Override
    public ResponseVO<Map<String, Integer>> addArticleInCircle(Integer circleId, AddCircleArticleForm form, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        User user = userMapper.selectByPrimaryKey(userId);
        if (UserStateEnum.MUTE.getCode().equals(user.getState())) {
            return ResponseVO.error(ResponseEnum.MUTE);
        }
        if (userDailyStatisticsUtils.isAddPostLimited(userId)) {
            return ResponseVO.error(ResponseEnum.REACH_PUBLISH_LIMIT);
        }
        Integer role = userCircleMapper.selectRoleOrNull(userId, circleId);
        if (role == null || role < UserCircleRoleEnum.FOLLOW.getCode()) {
            return ResponseVO.error(ResponseEnum.HAVE_NOT_PERMISSION);
        }
        Post article = new Post();
        article.setType(PostTypeEnum.ARTICLE.getCode());
        article.setArticleText(form.getPureText());
        article.setArticleCover(form.getCover());
        article.setAuthorId(userId);
        article.setBody(form.getBody());
        article.setVisibleOutsideCircle(false);
        article.setArticleTitle(form.getTitle());
        int result = postMapper.insertSelective(article);
        if (result != 1) {
            log.error("圈子内发布文章时出现错误,数据库表'post'插入失败");
            return ResponseVO.error(ResponseEnum.ERROR);
        }
        // MQ更新用户当日发帖子数量，更新用户经验
        rabbitTemplate.convertAndSend("dailyStatisticsExchange", "addPost", userId);
        // 通过MQ同步数据到ES
        rabbitTemplate.convertAndSend("MySQL2ESPostExchange", "add", article);
        PostCircle postCircle = new PostCircle();
        postCircle.setCircleId(circleId);
        postCircle.setPostId(article.getId());
        int result1 = postCircleMapper.insertSelective(postCircle);
        if (result1 != 1) {
            log.error("圈子内发布文章时出现错误,数据库表'post_circle'插入失败");
            return ResponseVO.error(ResponseEnum.ERROR);
        }
        rabbitTemplate.convertAndSend("updateHeat", "circle", circleId);
        Map<String, Integer> map = new HashMap<>(1);
        map.put("postId", article.getId());
        return ResponseVO.success(map);
    }

    @Override
    public ResponseVO<PostVO> getPost(Integer postId, String token) {
        Integer myId = null;
        boolean login = false;
        if (!StringUtils.isEmpty(token)) {
            if (jwtUtils.validateToken(token)) {
                myId = jwtUtils.getIdFromToken(token);
                login = true;
            }
        }
        Post post = postMapper.selectByPrimaryKey(postId);
        if (post == null) {
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "帖子不存在");
        }
        Integer postType = post.getType();
        PostVO postVO = new PostVO();
        postVO.setType(postType);
        if (PostTypeEnum.POST.getCode().equals(postType)) {
            if (post.getPostMediaUrls() != null && !StringUtils.isEmpty(post.getPostMediaUrls())) {
                List<String> mediaUrlList;
                String[] mediaUrl = post.getPostMediaUrls().split(";");
                mediaUrlList = Arrays.asList(mediaUrl);
                postVO.setMediaUrls(mediaUrlList);
            }
        }
        if (PostTypeEnum.ARTICLE.getCode().equals(postType)) {
            postVO.setTitle(post.getArticleTitle());
            postVO.setPureText(post.getArticleText());
            postVO.setCover(post.getArticleCover());
        }
        postVO.setPostId(post.getId());
        User author = userMapper.selectByPrimaryKey(post.getAuthorId());
        postVO.setAuthorId(author.getId());
        postVO.setAuthorName(author.getNickname());
        postVO.setAuthorAvatar(author.getAvatarUrl());
        postVO.setAuthorDescription(author.getDescription());
        postVO.setAuthorLevel(author.getLevel());
        postVO.setBody(post.getBody());
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        postVO.setCreateTime(simpleDateFormat.format(post.getCreateTime()));
        Integer likesNum = userPostLikeMapper.countByPostId(postId);
        Integer favoritesNum = userPostFavoriteMapper.countByPostId(postId);
        Integer coinsNum = userPostCoinMapper.countByPostId(postId);
        Integer commentsNum = commentMapper.countCommentsByPostId(postId);
        postVO.setLikeNum(likesNum);
        postVO.setFavoriteNum(favoritesNum);
        postVO.setCoinsNum(coinsNum);
        postVO.setCommentNum(commentsNum);
        boolean liked = false;
        boolean coined = false;
        boolean favorited = false;
        if (login) {
            UserPostLike userPostLike = userPostLikeMapper.selectByUserIdAndPostId(myId, post.getId());
            UserPostFavorite userPostFavorite = userPostFavoriteMapper.selectByUserIdAndPostId(myId, post.getId());
            UserPostCoin userPostCoin = userPostCoinMapper.selectByUserIdAndPostId(myId, post.getId());
            if (userPostLike != null && userPostLike.getState().equals(1)) {
                liked = true;
            }
            if (userPostFavorite != null && userPostFavorite.getState().equals(1)) {
                favorited = true;
            }
            if (userPostCoin != null && userPostCoin.getState().equals(1)) {
                coined = true;
            }
        }
        postVO.setLiked(liked);
        postVO.setCoined(coined);
        postVO.setFavorited(favorited);

        // 设置@的用户列表
        if (post.getAtIds() != null && !StringUtils.isEmpty(post.getAtIds())) {
            List<Map<String, Object>> atMapList = new ArrayList<>();
            String[] atIds = post.getAtIds().split(";");
            for (String atId : atIds) {
                User user = userMapper.selectByPrimaryKey(Integer.parseInt(atId));
                Map<String, Object> atMap = new HashMap<>(2);
                atMap.put("id", user.getId());
                atMap.put("nickName", user.getNickname());
                atMapList.add(atMap);
            }
            postVO.setAts(atMapList);
        }

        // 设置自定义标签
        if (post.getTags() != null && !StringUtils.isEmpty(post.getTags())) {
            List<String> tagList;
            String[] tags = post.getTags().split(";");
            tagList = Arrays.asList(tags);
            // 历史遗留原因导致要用map存
            List<Map<String, String>> tagMapList = new ArrayList<>();
            for (String tag : tagList) {
                Map<String, String> tagMap = new HashMap<>(2);
                tagMap.put("name", tag);
                tagMapList.add(tagMap);
            }
            postVO.setTags(tagMapList);
        }
        return ResponseVO.success(postVO);
    }

    @Override
    public ResponseVO deletePost(Integer postId, String token) {
        if (StringUtils.isEmpty(token)) {
            return ResponseVO.error(ResponseEnum.NEED_LOGIN);
        }
        if (!jwtUtils.validateToken(token)) {
            return ResponseVO.error(ResponseEnum.TOKEN_VALIDATE_FAILED);
        }
        Post post = postMapper.selectByPrimaryKey(postId);
        if (post == null) {
            return ResponseVO.error(ResponseEnum.PARAM_ERROR, "帖子不存在");
        }
        Integer userId = jwtUtils.getIdFromToken(token);
        if (!post.getAuthorId().equals(userId)) {
            return ResponseVO.error(ResponseEnum.HAVE_NOT_PERMISSION);
        }
        // 删除帖子
        postMapper.deleteByPrimaryKey(postId);
        // 通过MQ同步删除ES数据
        rabbitTemplate.convertAndSend("MySQL2ESPostExchange", "delete", post);
        // 删除帖子下面的评论对应的用户-评论关系表，这样用户被点赞数不会减只会加
        List<Integer> commentIdList = commentMapper.selectIdsByPostId(postId);
        for (Integer commentId : commentIdList) {
            userCommentLikeMapper.deleteByCommentId(commentId);
            userCommentCoinMapper.deleteByCommentId(commentId);
        }
        commentMapper.deleteByPostId(postId);
        // 删除用户-帖子-点赞关联动作
        userPostLikeMapper.deleteByPostId(postId);
        // 删除用户-帖子-收藏关联动作
        userPostFavoriteMapper.deleteByPostId(postId);
        // 删除用户-帖子-投币关联动作
        userPostCoinMapper.deleteByPostId(postId);
        // 删除帖子和分类的关系
        postCategoryMapper.deleteByPostId(postId);
        // 删除帖子和圈子的关系
        postCircleMapper.deleteByPostId(postId);
        return ResponseVO.success();
    }

}
