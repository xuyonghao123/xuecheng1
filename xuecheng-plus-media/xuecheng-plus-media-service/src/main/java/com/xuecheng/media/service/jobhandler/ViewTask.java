package com.xuecheng.media.service.jobhandler;

import com.xuecheng.base.utils.Mp4VideoUtil;
import com.xuecheng.base.utils.VideoUtil;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileProcessService;
import com.xuecheng.media.service.MediaFileService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.omg.SendingContext.RunTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.xml.ws.Action;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * XxlJob开发示例（Bean模式）
 *
 * 开发步骤：
 *      1、任务开发：在Spring Bean实例中，开发Job方法；
 *      2、注解配置：为Job方法添加注解 "@XxlJob(value="自定义jobhandler名称", init = "JobHandler初始化方法", destroy = "JobHandler销毁方法")"，注解value值对应的是调度中心新建任务的JobHandler属性的值。
 *      3、执行日志：需要通过 "XxlJobHelper.log" 打印执行日志；
 *      4、任务结果：默认任务结果为 "成功" 状态，不需要主动设置；如有诉求，比如设置任务结果为失败，可以通过 "XxlJobHelper.handleFail/handleSuccess" 自主设置任务结果；
 *
 * @author xuxueli 2019-12-11 21:52:51
 */
@Component
public class ViewTask {
    private static Logger log = LoggerFactory.getLogger(ViewTask.class);
    @Autowired
    private MediaFileProcessService mediaFileProcessService;
    @Autowired
    private MediaFileService mediaFileService;

    @Value("${videoprocess.ffmpegpath}")
    String ffmpegpath;
    /**
     * 2、分片广播任务
     */
    @XxlJob("videoJobHandler")
    public void videoJobHandler() throws Exception {

        // 分片参数
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        List<MediaProcess> mediaProcessList = null;
        int size = 0;

        //取出cpu核心数作为一次处理数据的条数
        int i = Runtime.getRuntime().availableProcessors();
        //一次处理视频数量不要超过cpu核心数
        mediaProcessList = mediaFileProcessService.getMediaProcessList(shardIndex, shardTotal, i);
        size = mediaProcessList.size();
        log.debug("取出待处理视频任务{}条", size);
        if (size <= 0) {
            return;
        }

        //启动size个线程的线程池
        ExecutorService threadPool = Executors.newFixedThreadPool(size);
        //计数器
        CountDownLatch countDownLatch = new CountDownLatch(size);

        mediaProcessList.forEach(mediaProcess -> {
            threadPool.execute(()->{
                try {
                    //任务id
                    Long taskId = mediaProcess.getId();
                    //抢占任务
                    boolean b = mediaFileProcessService.startTask(taskId);
                    if (!b) {
                        log.debug("抢占任务失败:{}", b);
                        return;
                    }
                    log.debug("开始执行任务:{}", mediaProcess);
                    //下边是处理逻辑
                    //桶
                    String bucket = mediaProcess.getBucket();
                    //存储路径
                    String objectName = mediaProcess.getFilePath();
                    //原始视频的md5值
                    String fileId = mediaProcess.getFileId();
                    //原始文件名称
                    String filename = mediaProcess.getFilename();

                    //将要下载的文件下载到到服务器上
                    File originalFile = mediaFileService.downloadFileFromMinIO(bucket, objectName);
                    if (originalFile == null) {
                        log.debug("下载待处理文件失败,originalFile:{}", mediaProcess.getBucket().concat(mediaProcess.getFilePath()));
                        //保存任务处理失败的结果
                        mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(), "3", fileId, null, "下载待处理文件失败");
                        return;
                    }
                    //源mp4视频路径
                    String video_path = originalFile.getAbsolutePath();
                    //转换后mp4文件的名称
                    String mp4_name = fileId + ".mp4";
                    //先创建一个临时文件，作为转换后的文件
                    File mp4File = null;
                    try {
                        mp4File = File.createTempFile("minio", ".mp4");
                    } catch (IOException e) {
                        log.debug("创建临时文件异常,{}", e.getMessage());
                        mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(), "3", fileId, null, "创建临时文件异常");
                        return;
                    }
                    //转换后mp4文件的路径
                    String mp4_Path = mp4File.getAbsolutePath();
                    //创建工具类对象
                    Mp4VideoUtil mp4VideoUtil = new Mp4VideoUtil(ffmpegpath, video_path, mp4_name, mp4_Path);
                    //开始视频转换，成功返回success
                    String result = mp4VideoUtil.generateMp4();
                    if (!result.equals("success")) {

                        log.debug("视频转码失败，原因:{},bucket:{},objectName:{}", result, bucket, objectName);
                        mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(), "3", fileId, null, "视频转码失败");
                        return;
                    }
                    //mp4在minio的存储路径
                    String s = getFilePath(fileId, ".mp4");
                    //上传到minio
                    boolean b1 = mediaFileService.addMediaFilesToMinio(mp4File.getAbsolutePath(), "video/mp4", bucket, s);
                    if (!b1){
                        log.error("上传视频失败或入库失败,视频地址:{}", bucket + objectName);
                        //最终还是失败了
                        mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(), "3", fileId, null, "处理后视频上传或入库失败");
                    }
                    //mp4的url拼接
                    String url = "/" + bucket + "/" + s;
                    //保存任务状态为成功
                    mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(), "2", fileId, url, null);

                }finally {
                    //计数器减1
                    countDownLatch.countDown();
                }

            });
        });
        //等待,给一个充裕的超时时间,防止无限等待，到达超时时间还没有处理完成则结束任务
        countDownLatch.await(30, TimeUnit.MINUTES);
    }
    private String getFilePath(String fileMd5,String fileExt){
        return   fileMd5.substring(0,1) + "/" + fileMd5.substring(1,2) + "/" + fileMd5 + "/" +fileMd5 +fileExt;
    }

}
