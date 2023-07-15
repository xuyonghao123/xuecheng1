package com.xuecheng.media;

import com.google.common.io.FileBackedOutputStream;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import io.minio.*;
import io.minio.errors.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class MinioTest {

    /**
     * @description 测试MinIO
     * @author xyh
     * @date 2023/5/15
     * @version 1.0
     */

    static MinioClient minioClient =
            MinioClient.builder()
                    .endpoint("http://192.168.101.65:9000")
                    .credentials("minioadmin", "minioadmin")
                    .build();

    //上传文件
    @Test
    public void uploadtest(){

        //根据扩展名取出mimeType
        ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(".mp4");
        String mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;//通用mimeType，字节流
        if(extensionMatch!=null){
            mimeType = extensionMatch.getMimeType();
        }
        try {
            UploadObjectArgs testbucket = UploadObjectArgs.builder()
                    .bucket("testbucket")
//                    .object("test001.mp4")
                    .object("Day5-07.nacos-配置中心-导入配置.mp4")//添加子目录
                    .filename("D:\\BaiduNetdiskDownload\\学成在线\\day05 媒资管理 Nacos Gateway MinIO\\Day5-07.nacos-配置中心-导入配置.mp4")
                    .contentType(mimeType)//默认根据扩展名确定文件内容类型，也可以指定
                    .build();
            minioClient.uploadObject(testbucket);
            System.out.println("上传成功");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("上传失败");
        }

    }

    //删除文件
    @Test
    public void removetest(){
        try {
            RemoveObjectArgs testbucket = RemoveObjectArgs.builder()
                    .bucket("testbucket")
                    .object("Day5-07.nacos-配置中心-导入配置.mp4")
                    .build();
            minioClient.removeObject(testbucket);
            System.out.println("删除成功");
        }catch (Exception e){
            e.printStackTrace();
            System.out.println("删除失败");
        }


    }

    @Test
    public void gettest() throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        GetObjectArgs testbucket = GetObjectArgs.builder().bucket("testbucket")
                .object("Day5-07.nacos-配置中心-导入配置.mp4")
                .build();

        FilterInputStream inputStream = minioClient.getObject(testbucket);
        FileOutputStream outputStream = new FileOutputStream(new File("D:\\BaiduNetdiskDownload\\学成在线\\1\\1.mp4"));
        IOUtils.copy(inputStream,outputStream);

        //校验文件完整性，将文件的内容进行md5
        //获取原始文件的md5
        //String source_md5 = DigestUtils.md5Hex(inputStream);
        String source_md5 = DigestUtils.md5Hex(new FileInputStream(new File("D:\\BaiduNetdiskDownload\\学成在线\\day05 媒资管理 Nacos Gateway MinIO\\Day5-07.nacos-配置中心-导入配置.mp4")));
        //获取下载下的文件的md5
        String local_md5 = DigestUtils.md5Hex(new FileInputStream(new File("D:\\BaiduNetdiskDownload\\学成在线\\1\\1.mp4")));

        if (source_md5.equals(local_md5)){
            System.out.println("下载成功");
        }
    }


    //将分块文件上传minio
    @Test
    public void uploadChunk() throws IOException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        for (int i = 0;i<10;i++) {
            UploadObjectArgs testChunk = UploadObjectArgs.builder()
                    .bucket("testbucket")
//                    .object("test001.mp4")
                    .filename("D:\\develop\\upload\\chunk\\"+i)
                    .object("chunk/" + i)//添加子目录
                    //.contentType(mimeType)//默认根据扩展名确定文件内容类型，也可以指定
                    .build();
            minioClient.uploadObject(testChunk);
            System.out.println("上传分块"+i);
        }
    }

    @Test
    public void testMerge() throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {

        List<ComposeSource> sources = new ArrayList<>();
        for (int i = 0;i<10;i++) {
            ComposeSource composeSource = ComposeSource.builder().bucket("testbucket").object("chunk/" + i).build();
            sources.add(composeSource);
        }
        ComposeObjectArgs mergeChunk = ComposeObjectArgs.builder()
                .bucket("testbucket")
                .object("merge01.mp4")
                .sources(sources)
                .build();

        minioClient.composeObject(mergeChunk);
    }

}
