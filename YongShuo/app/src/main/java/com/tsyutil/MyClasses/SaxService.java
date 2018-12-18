package com.tsyutil.MyClasses;

        import java.io.InputStream;
        import java.util.HashMap;
        import java.util.List;

        import javax.xml.parsers.SAXParser;
        import javax.xml.parsers.SAXParserFactory;

public class SaxService {
    public SaxService() {
    }
    /**
     * 网络xml解析方式
     * */
    public static List<HashMap<String, String>> readXML(
            InputStream inputStream, String nodeName) {
        List<HashMap<String, String>>list=null;
        try {
            //创建一个解析xml工厂对象
            SAXParserFactory saxParserFactory=SAXParserFactory.newInstance();
            SAXParser parser=saxParserFactory.newSAXParser();//解析xml
            SaxXml myHandler=new SaxXml(nodeName);
            parser.parse(inputStream, myHandler);
            list=myHandler.getList();
            inputStream.close();//关闭io
        } catch (Exception e) {
            // TODO: handle exception
        }
        return list;
    }
}

