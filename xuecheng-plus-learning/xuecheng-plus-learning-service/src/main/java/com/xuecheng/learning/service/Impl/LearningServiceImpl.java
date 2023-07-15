package com.xuecheng.learning.service.Impl;

import com.xuecheng.base.execption.XueChengPlusException;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.learning.feignclient.ContentServiceClient;
import com.xuecheng.learning.feignclient.MediaServiceClient;
import com.xuecheng.learning.model.dto.XcCourseTablesDto;
import com.xuecheng.learning.service.LearningService;
import com.xuecheng.learning.service.MyCourseTablesService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LearningServiceImpl implements LearningService {
    @Autowired
    private MyCourseTablesService myCourseTablesService;
    @Autowired
    private MediaServiceClient mediaServiceClient;
    @Autowired
    private ContentServiceClient contentServiceClient;
    @Override
    public RestResponse<String> getVideo(String userId, Long courseId, Long teachplanId, String mediaId) {

        //查询课程信息
        CoursePublish coursepublish = contentServiceClient.getCoursepublish(courseId);
        if(coursepublish==null){
            XueChengPlusException.cast("课程信息不存在");
        }
        if (StringUtils.isNotEmpty(userId)){
            //用户已登录
            //获取学习资格
            XcCourseTablesDto learningStatus = myCourseTablesService.getLearningStatus(userId, courseId);
            String learnStatus = learningStatus.getLearnStatus();
            if ("702002".equals(learnStatus)){
                RestResponse.validfail("无法学习，因为没有选课或选课后未支付");
            }else if ("702003".equals(learnStatus)){
                RestResponse.validfail("您的选课已过期，需要申请续期或者重新支付");
            }else {
                return mediaServiceClient.getPlayUrlByMediaId(mediaId);
            }
            //未登录或未选课判断是否收费
            String charge = coursepublish.getCharge();
            if(charge.equals("201000")){//免费可以正常学习
                //远程调用媒资服务获取视频
                return mediaServiceClient.getPlayUrlByMediaId(mediaId);
            }

        }
        return RestResponse.validfail("请购买课程后继续学习");
    }
}
