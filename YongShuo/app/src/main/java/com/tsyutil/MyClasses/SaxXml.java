package com.tsyutil.MyClasses;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SaxXml extends DefaultHandler {

        private HashMap<String, String> map = null;// 存储单个解析的完整对象
        List<HashMap<String, String>> list = null;// 存储所有的解析对象
        private String currentTag = null;// 正在解析元素的标签
        private String currentValue = null;// 正在解析元素的值
        private String nodeName = null;// 当前解析节点名称
        //解析顺序:startDocument(),startElement(),characters(),endElement();

        public SaxXml(String nodeName) {
            super();
            this.nodeName = nodeName;
        }

        public List<HashMap<String, String>> getList() {
            return list;
        }

        /**
         * xml文件开始解析时候调用的方法
         * */
        @Override
        public void startDocument() throws SAXException {
            // TODO Auto-generated method stub
            list = new ArrayList<HashMap<String, String>>();
            super.startDocument();
        }

        /**
         * 解析到节点开头调用方法<name>
         * */
        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            // TODO Auto-generated method stub
            if (qName.equals(nodeName)) {
                map = new HashMap<String, String>();
            }
            if (attributes != null && map != null) {
                for (int i = 0; i < attributes.getLength(); i++) {
                    map.put(attributes.getQName(i), attributes.getValue(i));
                }
            }
            currentTag = qName;
        }

        /**
         * 解析到节点开头结尾中间夹的文字所调用的方法
         * */
        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            // TODO Auto-generated method stub
            if (currentTag != null && map != null) {
                currentValue = new String(ch, start, length);
                if (!currentValue.trim().equals("") && !currentValue.trim().equals("\n")) {
                    map.put(currentTag, currentValue);
                }
            }
            currentTag = null;// 把当前节点对应的值和标签设置为空
            currentValue = null;
        }

        /**
         * 解析到节点结尾调用方法</name>
         * */
        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            // 遇到结束标记时候
            if (qName.equals(nodeName)) {
                list.add(map);
                map = null;
            }
        }
    }
