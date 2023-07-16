package com.xuecheng.learning.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xuecheng.base.execption.XueChengPlusException;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.learning.feignclient.ContentServiceClient;
import com.xuecheng.learning.mapper.XcChooseCourseMapper;
import com.xuecheng.learning.mapper.XcCourseTablesMapper;
import com.xuecheng.learning.model.dto.MyCourseTableParams;
import com.xuecheng.learning.model.dto.XcChooseCourseDto;
import com.xuecheng.learning.model.dto.XcCourseTablesDto;
import com.xuecheng.learning.model.po.XcChooseCourse;
import com.xuecheng.learning.model.po.XcCourseTables;
import com.xuecheng.learning.service.MyCourseTablesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class MyCourseTablesServiceImpl implements MyCourseTablesService {
    @Autowired
    private XcChooseCourseMapper chooseCourseMapper;
    @Autowired
    private XcCourseTablesMapper courseTablesMapper;
    @Autowired
    private ContentServiceClient contentServiceClient;

    @Transactional
    @Override
    public XcChooseCourseDto addChooseCourse(String userId, Long courseId) {
        //远程调用接口查询课程信息
        CoursePublish coursepublish = contentServiceClient.getCoursepublish(courseId);
        if (coursepublish == null) {
            XueChengPlusException.cast("课程不存在！");
        }
        //课程的收费标准
        String charge = coursepublish.getCharge();
        //选课记录
        XcChooseCourse chooseCourse = null;//选课记录
        if ("201000".equals(charge)) {//课程免费
            //添加免费课程
            chooseCourse = addFreeCoruse(userId, coursepublish);
            //添加到我的课程表
            XcCourseTables xcCourseTables = addCourseTabls(chooseCourse);
        } else {
            //添加收费课程
            chooseCourse = addChargeCoruse(userId, coursepublish);
        }

        XcChooseCourseDto xcChooseCourseDto = new XcChooseCourseDto();
        BeanUtils.copyProperties(chooseCourse,xcChooseCourseDto);
        //获取学习资格
        XcCourseTablesDto learningStatus = getLearningStatus(userId, courseId);
        xcChooseCourseDto.setLearnStatus(learningStatus.getLearnStatus());
        return xcChooseCourseDto;
    }



    //添加免费课程,免费课程加入选课记录表、我的课程表
    public XcChooseCourse addFreeCoruse(String userId, CoursePublish coursepublish) {
        //查询选课记录表是否存在免费的且选课成功的订单
        LambdaQueryWrapper<XcChooseCourse> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper = queryWrapper.eq(XcChooseCourse::getUserId, userId)
                .eq(XcChooseCourse::getCourseId, coursepublish.getId())
                .eq(XcChooseCourse::getOrderType, "700001")//免费课程
                .eq(XcChooseCourse::getStatus, "701001");//选课成功
        List<XcChooseCourse> xcChooseCourses = chooseCourseMapper.selectList(queryWrapper);
        if (xcChooseCourses != null && xcChooseCourses.size() > 0) {
            return xcChooseCourses.get(0);
        }
        //添加选课记录信息
        XcChooseCourse xcChooseCourse = new XcChooseCourse();
        xcChooseCourse.setCourseId(coursepublish.getId());
        xcChooseCourse.setCourseName(coursepublish.getName());
        xcChooseCourse.setCoursePrice(0f);//免费课程价格为0
        xcChooseCourse.setUserId(userId);
        xcChooseCourse.setCompanyId(coursepublish.getCompanyId());
        xcChooseCourse.setOrderType("700001");//免费课程
        xcChooseCourse.setCreateDate(LocalDateTime.now());
        xcChooseCourse.setStatus("701001");//选课成功

        xcChooseCourse.setValidDays(365);//免费课程默认365
        xcChooseCourse.setValidtimeStart(LocalDateTime.now());
        xcChooseCourse.setValidtimeEnd(LocalDateTime.now().plusDays(365));
        chooseCourseMapper.insert(xcChooseCourse);

        return xcChooseCourse;
    }

    //获取学习资格
    /**
     * [{"code":"702001","desc":"正常学习"},{"code":"702002","desc":"没有选课或选课后没有支付"},{"code":"702003","desc":"已过期需要申请续期或重新支付"}]
     */
    @Override
    public XcCourseTablesDto getLearningStatus(String userId, Long courseId) {
        XcCourseTables xcCourseTables = getXcCourseTables(userId, courseId);
        if (xcCourseTables ==null){
            XcCourseTablesDto xcCourseTablesDto = new XcCourseTablesDto();
            //没有选课或选课后没有支付
            xcCourseTablesDto.setLearnStatus("702002");
            return xcCourseTablesDto;
        }
        XcCourseTablesDto xcCourseTablesDto = new XcCourseTablesDto();
        BeanUtils.copyProperties(xcCourseTables,xcCourseTablesDto);
        //是否过期,true过期，false未过期
        boolean isExpires = xcCourseTables.getValidtimeEnd().isBefore(LocalDateTime.now());
        if(!isExpires){
            //正常学习
            xcCourseTablesDto.setLearnStatus("702001");
            return xcCourseTablesDto;

        }else{
            //已过期
            xcCourseTablesDto.setLearnStatus("702003");
            return xcCourseTablesDto;
        }
    }

    @Override
    public boolean saveChooseCourseStauts(String courseId) {
        //根据courseId查询一下选课表
        XcChooseCourse xcChooseCourse = chooseCourseMapper.selectById(courseId);
        if (xcChooseCourse == null){
            log.debug("接收购买课程的消息，根据选课id从数据库查不到选课记录，选课id:{}",courseId);
            return false;
        }
        String status = xcChooseCourse.getStatus();
        //只有未支付时更新为已支付
        if ("701002".equals(status)){
            //更新选课支付记录为成功支付
            xcChooseCourse.setStatus("7001001");
            int i = chooseCourseMapper.updateById(xcChooseCourse);
            if (i<0){
                log.debug("更新选课记录失败:{}",xcChooseCourse);
                XueChengPlusException.cast("更新选课记录失败");
                return false;
            }

            //向我的课程表插入
            XcCourseTables xcCourseTables = addCourseTabls(xcChooseCourse);
            return true;

        }
        return false;

    }

    @Override
    public PageResult<XcCourseTables> mycourestabls(MyCourseTableParams params) {
        //页码
        int page = params.getPage();
        //每页记录数
        int size = params.getSize();
        String userId = params.getUserId();

        Page<XcCourseTables> courseTablesPage = new Page<>(page, size);
        LambdaQueryWrapper<XcCourseTables> la = new LambdaQueryWrapper<XcCourseTables>().eq(XcCourseTables::getUserId, userId);


        Page<XcCourseTables> result = courseTablesMapper.selectPage(courseTablesPage, la);
        List<XcCourseTables> records = result.getRecords();
        long total = result.getTotal();
        PageResult<XcCourseTables> xcCourseTablesPageResult = new PageResult<>(records, total, page, size);
        return xcCourseTablesPageResult;
    }

    //添加收费课程
    public XcChooseCourse addChargeCoruse(String userId, CoursePublish coursepublish) {
        //如果存在待支付交易记录直接返回
        LambdaQueryWrapper<XcChooseCourse> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper = queryWrapper.eq(XcChooseCourse::getUserId, userId)
                .eq(XcChooseCourse::getCourseId, coursepublish.getId())
                .eq(XcChooseCourse::getOrderType, "700002")//收费订单
                .eq(XcChooseCourse::getStatus, "701002");//待支付
        List<XcChooseCourse> xcChooseCourses = chooseCourseMapper.selectList(queryWrapper);
        if (xcChooseCourses.size() > 0 && xcChooseCourses != null) {
            return xcChooseCourses.get(0);
        }
        XcChooseCourse xcChooseCourse = new XcChooseCourse();
        xcChooseCourse.setCourseId(coursepublish.getId());
        xcChooseCourse.setCourseName(coursepublish.getName());
        xcChooseCourse.setCoursePrice(coursepublish.getPrice());
        xcChooseCourse.setUserId(userId);
        xcChooseCourse.setCompanyId(coursepublish.getCompanyId());
        xcChooseCourse.setOrderType("700002");//收费课程
        xcChooseCourse.setCreateDate(LocalDateTime.now());
        xcChooseCourse.setStatus("701002");//待支付

        xcChooseCourse.setValidDays(coursepublish.getValidDays());
        xcChooseCourse.setValidtimeStart(LocalDateTime.now());
        xcChooseCourse.setValidtimeEnd(LocalDateTime.now().plusDays(coursepublish.getValidDays()));
        chooseCourseMapper.insert(xcChooseCourse);

        return xcChooseCourse;
    }

    //添加到我的课程表
    public XcCourseTables addCourseTabls(XcChooseCourse xcChooseCourse) {
        //选课记录完成且未过期可以添加课程到课程表
        String status = xcChooseCourse.getStatus();
        if (!"701001".equals(status)) {
            XueChengPlusException.cast("选课未成功，无法添加到课程表");
        }
        //查询我的课程表
        XcCourseTables xcCourseTables = getXcCourseTables(xcChooseCourse.getUserId(), xcChooseCourse.getCourseId());
        if (xcCourseTables != null) {
            return xcCourseTables;
        }
        XcCourseTables xcCourseTablesNew = new XcCourseTables();
        xcCourseTablesNew.setChooseCourseId(xcChooseCourse.getId());
        xcCourseTablesNew.setCourseId(xcChooseCourse.getCourseId());
        xcCourseTablesNew.setUserId(xcChooseCourse.getUserId());
        xcCourseTablesNew.setCompanyId(xcChooseCourse.getCompanyId());
        xcCourseTablesNew.setCourseName(xcChooseCourse.getCourseName());
        xcCourseTablesNew.setCreateDate(xcChooseCourse.getCreateDate());
        xcCourseTablesNew.setValidtimeStart(xcChooseCourse.getValidtimeStart());
        xcCourseTablesNew.setValidtimeEnd(xcChooseCourse.getValidtimeEnd());
        xcCourseTablesNew.setCourseType(xcChooseCourse.getOrderType());
        courseTablesMapper.insert(xcCourseTablesNew);

        return xcCourseTablesNew;
    }

    public XcCourseTables getXcCourseTables(String userId,Long courseId){
        XcCourseTables xcCourseTables = courseTablesMapper.selectOne(new LambdaQueryWrapper<XcCourseTables>().eq(XcCourseTables::getUserId, userId).eq(XcCourseTables::getCourseId, courseId));
        return xcCourseTables;

    }

}
