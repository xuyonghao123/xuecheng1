package com.xuecheng.media;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import java.io.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


public class BigFileTest {

    //测试分块
    @Test
    public void testChunk() throws IOException {
        //源文件
        File sourceFile = new File("D:\\BaiduNetdiskDownload\\学成在线\\day06 断点续传 xxl-job\\Day6-01.上传视频-什么是断点续传.mp4");
        //分块文件存储路径
        String chunkFilePath = "D:\\develop\\upload\\chunk\\";
        //分块文件的大小
        int chunkSize = 1024*1024*5;  //1M
        //分块文件的个数
        int chunkNum = (int) Math.ceil(sourceFile.length()*1.0/chunkSize);
        //使用流从源文件中读数据像分块文件中写数据
        RandomAccessFile rar_r = new RandomAccessFile(sourceFile, "r");
        //缓冲区
        byte[] bytes = new byte[1024];

        for (int i = 0; i < chunkNum; i++) {
            File chunkfile = new File(chunkFilePath + i);
            //分块文件写入流
            RandomAccessFile rar_w = new RandomAccessFile(chunkfile, "rw");
            int len = -1;

            while ((len = rar_r.read(bytes)) != -1) {
                rar_w.write(bytes, 0, len);
                if (chunkfile.length() >= chunkSize) {
                    break;
                }
            }
            rar_w.close();
        }
        rar_r.close();
    }

    //测试合并
    @Test
    public void testMerge() throws IOException {
         //分块文件目录
        File chunkFolder = new File("D:\\develop\\upload\\chunk\\");

        File sourceFile = new File("D:\\BaiduNetdiskDownload\\学成在线\\day06 断点续传 xxl-job\\Day6-01.上传视频-什么是断点续传.mp4");
        //合并后的文件在哪
        File mergeFile = new File("D:\\BaiduNetdiskDownload\\学成在线\\day06 断点续传 xxl-job\\Day6-01.上传视频-什么是断点续传-02.mp4");

        //取出所有分块文件
        File[] files = chunkFolder.listFiles();
        //将File数组转为list
        List<File> filesList = Arrays.asList(files);
        //对分块文件的排序
        Collections.sort(filesList, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                //按照升序排列，若为降序则o2-o1
                return Integer.parseInt(o1.getName())-Integer.parseInt(o2.getName());
            }
        });
        //向合并文件写的流
        RandomAccessFile rar_w = new RandomAccessFile(mergeFile, "rw");
        //缓冲区
        byte[] bytes = new byte[1024];
        //遍历分块文件，像合并的文件写
        for (File file : filesList) {
            RandomAccessFile rar_r = new RandomAccessFile(file, "r");
            int len = -1;
            while ((len = rar_r.read(bytes))!=-1){
                rar_w.write(bytes,0,len);
            }
            rar_r.close();
        }
        rar_w.close();
        //合并文件完成，对合并的文件进行校验
        FileInputStream fileInputStream = new FileInputStream(mergeFile);
        String merge = DigestUtils.md5Hex(fileInputStream);
        FileInputStream fileInputStream1 = new FileInputStream(sourceFile);
        String source = DigestUtils.md5Hex(fileInputStream1);
        if (merge.equals(source)){
            System.out.println("文件合并成功");
        }
    }
}
