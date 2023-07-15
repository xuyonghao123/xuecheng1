package com.xuecheng.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.mapper.MediaProcessHistoryMapper;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.model.po.MediaProcessHistory;
import com.xuecheng.media.service.MediaFileProcessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class MediaFIleProcessServiceImpl implements MediaFileProcessService {
    @Autowired
    private MediaProcessMapper mediaProcessMapper;
    @Autowired
    private MediaFilesMapper mediaFilesMapper;
    @Autowired
    private MediaProcessHistoryMapper historyMapper;
    @Override
    public List<MediaProcess> getMediaProcessList(int shardIndex, int shardTotal, int count) {
        List<MediaProcess> mediaProcesses = mediaProcessMapper.selectListByShardIndex(shardTotal, shardIndex, count);
        return mediaProcesses;
    }

    @Override
    public boolean startTask(long id) {
        int result = mediaProcessMapper.startTask(id);
        return result<=0?false:true;
    }

    @Override
    public void saveProcessFinishStatus(Long taskId, String status, String fileId, String url, String errorMsg) {
            //先查询待处理视频表，看看是否有这个任务
        MediaProcess mediaProcess = mediaProcessMapper.selectById(taskId);
        if (mediaProcess == null){
            return;
        }
        //处理失败，更新任务处理结果
        LambdaQueryWrapper<MediaProcess> eq = new LambdaQueryWrapper<MediaProcess>().eq(MediaProcess::getId, taskId);
        if ("3".equals(status)){
            MediaProcess mediaProcess1 = new MediaProcess();
            mediaProcess1.setStatus(status);
            mediaProcess1.setErrormsg(errorMsg);
            mediaProcess1.setFailCount(mediaProcess.getFailCount()+1);
            mediaProcessMapper.updateById(mediaProcess1);
            //mediaProcessMapper.update(mediaProcess1,eq);
            log.debug("更新任务处理状态为失败，任务信息:{}",mediaProcess1);
            return ;
        }
        //任务处理成功
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileId);
        if (mediaFiles != null){
            //更新媒资文件中的访问url
            mediaFiles.setUrl(url);
            mediaFilesMapper.updateById(mediaFiles);
        }
        //处理成功，更新url和状态
        mediaProcess.setUrl(url);
        mediaProcess.setStatus("2");
        mediaProcess.setFinishDate(LocalDateTime.now());
        mediaProcessMapper.updateById(mediaProcess);

        //添加到历史记录
        MediaProcessHistory mediaProcessHistory = new MediaProcessHistory();
        BeanUtils.copyProperties(mediaProcess,mediaProcessHistory);
        historyMapper.insert(mediaProcessHistory);

        //删除mediaProcess
        mediaProcessMapper.deleteById(mediaProcess.getId());

    }
}
