package com.xuecheng.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import com.sun.org.apache.bcel.internal.generic.FSUB;
import com.xuecheng.base.execption.XueChengPlusException;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.dto.QueryMediaParamsDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadFileResultDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileService;
import io.minio.*;
import io.minio.errors.*;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2022/9/10 8:58
 */
@Slf4j
@Service
public class MediaFileServiceImpl implements MediaFileService {

    @Autowired
    MediaFilesMapper mediaFilesMapper;
    @Autowired
    MinioClient minioClient;
    @Autowired
    MediaFileService currentProxy;
    @Autowired
    MediaProcessMapper mediaProcessMapper;

    @Value("${minio.bucket.files}")
    private String mediafiles;
 @Value("${minio.bucket.videofiles}")
    private String video;

    @Override
    public MediaFiles getMediaFiles(String mediaId) {
        MediaFiles mediaFiles = mediaFilesMapper.selectById(mediaId);
        return mediaFiles;
    }

    @Override
    public PageResult<MediaFiles> queryMediaFiels(Long companyId, PageParams pageParams, QueryMediaParamsDto queryMediaParamsDto) {

        //构建查询条件对象
        LambdaQueryWrapper<MediaFiles> queryWrapper = new LambdaQueryWrapper<>();

        //分页对象
        Page<MediaFiles> page = new Page<>(pageParams.getPageNo(), pageParams.getPageSize());
        // 查询数据内容获得结果
        Page<MediaFiles> pageResult = mediaFilesMapper.selectPage(page, queryWrapper);
        // 获取数据列表
        List<MediaFiles> list = pageResult.getRecords();
        // 获取数据总数
        long total = pageResult.getTotal();
        // 构建结果集
        PageResult<MediaFiles> mediaListResult = new PageResult<>(list, total, pageParams.getPageNo(), pageParams.getPageSize());
        return mediaListResult;

    }


    @Override
    public UploadFileResultDto uploadFile(Long companyId, UploadFileParamsDto uploadFileParamsDto, String localFilePath,String objectName) {
        //将文件上传到minio
        //得到文件名
     String filename = uploadFileParamsDto.getFilename();
     //获取本地文件
        File file = new File(localFilePath);
        if (!file.exists()){
            XueChengPlusException.cast("文件不存在");
        }
     //先得到扩展名
        //根据扩展名取出mimeType
     String extension = filename.substring(filename.lastIndexOf("."));
     String mimeType = getMimeType(extension);
     //文件目录
        String defaultFolderPath = getDefaultFolderPath();
        //文件的md5值
        String fileMd5 = getFileMd5(file);
        //存储到minio中的对象名(带目录)
        if (StringUtils.isEmpty(objectName)){
            objectName = defaultFolderPath+fileMd5+extension;
        }
        addMediaFilesToMinio(localFilePath,mimeType,mediafiles,objectName);
        uploadFileParamsDto.setFileSize(file.length());
        //将文件信息保存到数据库
        MediaFiles mediaFiles = currentProxy.addMediaFilesToDB(companyId, uploadFileParamsDto, fileMd5, mediafiles, objectName);
        //准备返回数据
        UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
        BeanUtils.copyProperties(mediaFiles, uploadFileResultDto);
        return uploadFileResultDto;
        
    }

    /**
     * 从minio下载文件
     * @param bucket 桶
     * @param objectName 对象名称
     * @return 下载后的文件
     */
    @Override
    public File downloadFileFromMinIO(String bucket,String objectName){
        //临时文件
        File minioFile = null;
        FileOutputStream outputStream = null;
        try {
            GetObjectArgs getObjectArgs = GetObjectArgs
                    .builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build();
            InputStream stream = minioClient.getObject(getObjectArgs);
            //创建临时文件
           minioFile =  File.createTempFile("minio",".merge");
            outputStream = new FileOutputStream(minioFile);
            IOUtils.copy(stream,outputStream);
            return minioFile;
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            if(outputStream!=null){
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return  null;
    }

    //将文件信息保存到数据库
    @Transactional
    public MediaFiles addMediaFilesToDB(Long companyId, UploadFileParamsDto uploadFileParamsDto, String fileMd5, String bucket,String objectName) {
        //从数据库查询文件
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles == null) {
            mediaFiles = new MediaFiles();
            //拷贝基本信息
            BeanUtils.copyProperties(uploadFileParamsDto, mediaFiles);
            mediaFiles.setId(fileMd5);
            mediaFiles.setFileId(fileMd5);
            mediaFiles.setCompanyId(companyId);
            mediaFiles.setUrl("/" + bucket + "/" + objectName);
            mediaFiles.setBucket(bucket);
            mediaFiles.setFilePath(objectName);
            mediaFiles.setCreateDate(LocalDateTime.now());
            mediaFiles.setAuditStatus("002003");
            mediaFiles.setStatus("1");
            //保存文件信息到文件表
            int insert = mediaFilesMapper.insert(mediaFiles);
            if (insert < 0) {
                log.error("保存文件信息到数据库失败,{}",mediaFiles.toString());
                XueChengPlusException.cast("保存文件信息失败");
            }
            addWaitingTask(mediaFiles);
            log.debug("保存文件信息到数据库成功,{}",mediaFiles.toString());
        }
        return mediaFiles;

    }

    /**
     * 添加待处理任务
     * @param mediaFiles 媒资文件信息
     * */
    private void addWaitingTask(MediaFiles mediaFiles) {
        //添加到待处理任务表
        //文件名称
        String filename = mediaFiles.getFilename();
        //文件扩展名
        String extension = filename.substring(filename.lastIndexOf("."));
        String mimeType = getMimeType(extension);
        //如果是avi视频添加到视频待处理表
        if (mimeType.equals("video/mp4")){
            MediaProcess mediaProcess = new MediaProcess();
            BeanUtils.copyProperties(mediaFiles,mediaProcess);
            mediaProcess.setStatus("1");//未处理
            mediaProcess.setUrl(null);
            mediaProcess.setFailCount(0);//失败次数默认为0
            mediaProcessMapper.insert(mediaProcess);
        }
    }

    @Override
    public RestResponse<Boolean> checkFile(String fileMd5) {
        //先查询数据库
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles != null) {
            String bucket = mediaFiles.getBucket();
            String filePath = mediaFiles.getFilePath();
            //如果数据库存在，在检查minio
            GetObjectArgs build = GetObjectArgs
                    .builder()
                    .bucket(bucket)
                    .object(filePath)
                    .build();
            try {
                FilterInputStream inputStream = minioClient.getObject(build);
                if (inputStream != null) {
                    //文件已存在
                    return RestResponse.success(true);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return RestResponse.success(false);
    }

    @Override
    public RestResponse<Boolean> checkChunk(String fileMd5, int chunkIndex) {
        //分块存储路径：md5前两位为子目录，chunk存储分块文件
        //获取md5前两位
        String getChunkFileFolderPath = GetChunkFileFolderPath(fileMd5);

            //如果数据库存在，在检查minio
            GetObjectArgs build = GetObjectArgs
                    .builder()
                    .bucket(video)
                    .object(getChunkFileFolderPath+chunkIndex)
                    .build();
            try {
                FilterInputStream inputStream = minioClient.getObject(build);
                if (inputStream != null) {
                    //文件已存在
                    return RestResponse.success(true);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        return RestResponse.success(false);
    }

    @Override
    public RestResponse uploadChunk(String fileMd5, int chunk, String localChunkFilePath) {
        String s = GetChunkFileFolderPath(fileMd5);
        String objectName = s+chunk;
        String mimeType = getMimeType(null);
        //将分块文件上传到minio
        boolean b = addMediaFilesToMinio(localChunkFilePath, mimeType, video, objectName);
        if (!b) {
            return RestResponse.validfail(false, "上传分块文件失败");
        }
        //上传文件成功
        log.debug("上传分块文件成功:{}",objectName);
        return RestResponse.success(true);
    }


    /**
     * @description 合并分块
     * @param companyId  机构id
     * @param fileMd5  文件md5
     * @param chunkTotal 分块总和
     * @param uploadFileParamsDto 文件信息
     * @return com.xuecheng.base.model.RestResponse
     * @author xyh
     * @date 2023/5/19 15:56
     */
    @Override
    public RestResponse mergechunks(Long companyId, String fileMd5, int chunkTotal, UploadFileParamsDto uploadFileParamsDto) {
        List<ComposeSource> sources = new ArrayList<>();
        //1.获取分块文件路径
        String chunkFileFolderPath = GetChunkFileFolderPath(fileMd5);
        for (int i = 0;i<chunkTotal;i++) {
            //分块文件组成list
            ComposeSource composeSource = ComposeSource.builder().bucket(video).object(chunkFileFolderPath + i).build();
            sources.add(composeSource);
        }
       // =====合并======
        //源文件名称
        String filename = uploadFileParamsDto.getFilename();
        //扩展名
        String extension = filename.substring(filename.lastIndexOf("."));
        String filePathByMd5 = getFilePathByMd5(fileMd5, extension);
        ComposeObjectArgs mergeChunk = ComposeObjectArgs.builder()
                .bucket(video)
                .object(filePathByMd5)//最终合并后的文件他的objectName
                .sources(sources)
                .build();

        try {
            minioClient.composeObject(mergeChunk);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("合并文件出错,bucket:{},object:{},错误信息:{}",video,filePathByMd5,e.getMessage());
            return RestResponse.validfail(false,"合并文件出错");
        }

        //2.合并后校验与源文件是否一致
        //先下载合并后的文件
        File file = downloadFileFromMinIO(video, filePathByMd5);
        if (file == null){
            log.debug("下载合并后文件失败,filePathByMd5:{}",filePathByMd5);
            return RestResponse.validfail(false,"下载合并后文件失败");
        }
        try(InputStream fileInputStream = new FileInputStream(file)) {

            //minio上文件的md5
            String md5Hex = DigestUtils.md5Hex(fileInputStream);
            //比较md5值，不一致则说明文件不完整
            if (!md5Hex.equals(fileMd5)){
                return RestResponse.validfail(false, "文件合并校验失败，最终上传失败。");
            }
            //文件大小
            uploadFileParamsDto.setFileSize(file.length());
        } catch (Exception e) {
            log.debug("校验文件失败,fileMd5:{},异常:{}",fileMd5,e.getMessage(),e);
            return RestResponse.validfail(false, "文件合并校验失败，最终上传失败。");
        }
        //3.将文件信息入库
        MediaFiles mediaFiles = currentProxy.addMediaFilesToDB(companyId, uploadFileParamsDto, fileMd5, video, filePathByMd5);
        if (mediaFiles == null){
            return RestResponse.validfail(false, "文件入库失败");
        }
        //4.清理分块文件
        clearChunkFiles(chunkFileFolderPath,chunkTotal);
        return RestResponse.success(true);
    }

    private static String GetChunkFileFolderPath(String fileMd5) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) +"/"+ fileMd5 + "/" + "chunk" + '/';
    }

    /**
     * 得到合并后的文件的地址
     * @param fileMd5 文件id即md5值
     * @param fileExt 文件扩展名
     * @return
     */
    private String getFilePathByMd5(String fileMd5,String fileExt){
        return   fileMd5.substring(0,1) + "/" + fileMd5.substring(1,2) + "/" + fileMd5 + "/" +fileMd5 +fileExt;
    }

    //将文件上传到minio
    public boolean addMediaFilesToMinio(String localFilePath, String mimeType, String bucket, String objectName) {
        try {
            UploadObjectArgs testbucket = UploadObjectArgs.builder()
                    .bucket(bucket)
//                    .object("test001.mp4")
                    .object(objectName)//添加子目录
                    .filename(localFilePath)
                    .contentType(mimeType)//默认根据扩展名确定文件内容类型，也可以指定
                    .build();
            minioClient.uploadObject(testbucket);
            log.debug("上传文件到minio成功,bucket:{},objectName:{}", bucket, objectName);
            System.out.println("上传成功");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("上传文件到minio出错,bucket:{},objectName:{},错误原因:{}", bucket, objectName, e.getMessage(), e);
            XueChengPlusException.cast("上传文件到文件系统失败");
        }
        return false;
    }


    //根据扩展名获取mimetype
    private static String getMimeType(String extension) {
        if (extension == null) {
            extension = "";
        }
        ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
        String mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;//通用mimeType，字节流
        if (extensionMatch != null) {
            mimeType = extensionMatch.getMimeType();
        }
        return mimeType;
    }

    //获取文件默认存储目录路径 年/月/日
    private String getDefaultFolderPath() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String fold = simpleDateFormat.format(new Date()).replace("-", "/") + "/";
        return fold;
    }

    //获取文件的md5值
    private String getFileMd5(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            String fileMd5 = DigestUtils.md5Hex(fileInputStream);
            return fileMd5;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    //清除分块文件
    private void clearChunkFiles(String chunkFileFolderPath,int chunkTotal) {
        List<DeleteObject> objects = new ArrayList<>();
        for (int i = 0;i<chunkTotal;i++) {
            //分块文件组成list
            DeleteObject composeSource =new DeleteObject(chunkFileFolderPath);
            objects.add(composeSource);
        }
        RemoveObjectsArgs removeObjectsArgs = RemoveObjectsArgs.builder().bucket(video).objects(objects).build();
        Iterable<Result<DeleteError>> results = minioClient.removeObjects(removeObjectsArgs);
        results.forEach(r->{
            DeleteError deleteError = null;
            try {
                deleteError = r.get();
            } catch (Exception e) {
                e.printStackTrace();
                log.error("清楚分块文件失败,objectname:{}",deleteError.objectName(),e);
            }
        });
    }

}
