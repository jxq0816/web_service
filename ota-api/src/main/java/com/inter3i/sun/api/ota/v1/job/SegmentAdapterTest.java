/*
 *
 * Copyright (c) 2017, inter3i.com. All rights reserved.
 * All rights reserved.
 *
 * Author: wangchaochao
 * Created: 2017/01/16
 * Description:
 *
 */

package com.inter3i.sun.api.ota.v1.job;

import com.inter3i.sun.api.ota.v1.config.MongoDBServerConfig;
import com.inter3i.sun.api.ota.v1.config.SegmentFieldsConfig;
import com.inter3i.sun.api.ota.v1.net.HttpUtils;
import com.inter3i.sun.api.ota.v1.util.NLPDataFormater;
import com.inter3i.sun.api.ota.v1.util.SegmentHelper;
import com.inter3i.sun.api.ota.v1.util.ValidateUtils;
import com.mongodb.client.MongoCollection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 *
 */
public class SegmentAdapterTest {
    private static final Logger logger = LoggerFactory.getLogger(SegmentAdapterTest.class);
    private final MongoDBServerConfig serverConfig;
    private final String cacheName;
    private final MongoCollection dbCollection;

    public SegmentAdapterTest(final MongoDBServerConfig serverConfig, final String cacheName, final MongoCollection dbCollection) {
        if (serverConfig == null) {
            String errorMsg = "create SegmentAdapter excption:serverConfig is null!";
            logger.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        if (dbCollection == null) {
            String errorMsg = "create SegmentAdapter excption:dbCollection is null!";
            logger.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        this.serverConfig = serverConfig;
        this.cacheName = cacheName;
        this.dbCollection = dbCollection;
    }

    /**
     * 对一个任务的所有数据进行分词处理
     * commonData 结构:<BR>
     * ---------+- taskId :任务ID
     * ---------+- dictPlan :任务ID
     * ---------+- taskParam :任务参数
     * ---------+- sourceid:入库时候需要sourceId
     * ---------+- datas:所有数据
     * ---------+- currentPort:当前端口
     * ---------+- currentHost:当前主机
     *
     * @param taskDataJson
     */
    public JSONObject doJsonSegmentFromNLP(JSONObject taskDataJson) {
        try {
            logger.info("Job:[SegmenteJob] --+-doSegmentFromNLP doc ...");
            long startTime = System.currentTimeMillis();

            //TODO 1. 是否需要对文章进行字段补全 即从solr中查询该篇文章的个别字段 因为solr中不支持增量更新???
            //TODO 2.补全非原创文章的情感字段(根据retweeted_guid字段从mysql中获取原创的情感字段)?? orig_emotion orig_business orig_emoBusiness
            JSONArray dictPlan = null;
            Object dictPlanTmp = taskDataJson.get("dictPlan");
            if (dictPlanTmp instanceof String && ((String) dictPlanTmp).equals("[[]]")) {
                dictPlan = new JSONArray();
                dictPlan.put(new JSONArray());
            } else {
                dictPlan = (JSONArray) dictPlanTmp;
            }

            String currentHost = taskDataJson.getString("currentHost");
            int currentPort = taskDataJson.getInt("currentPort");

            JSONArray grabDocs = taskDataJson.getJSONArray("datas");
            JSONObject grabDoc = null;
            JSONArray result = null;
            Map<String, Object> filedTermsMap = new HashMap<String, Object>(16);
            JSONArray resultDatas = null;
            JSONObject responseData = null;
            String segResult = null;
            for (int gdIdx = 0; gdIdx < grabDocs.length(); gdIdx++) {
                grabDoc = (JSONObject) grabDocs.get(gdIdx);
                result = new JSONArray();
                filedTermsMap.clear();

                //add by wangcc 处理空的字段
                Iterator keysIt = grabDoc.keys();
                String filedName = null;
                while (keysIt.hasNext()) {
                    filedName = (String) keysIt.next();
                    if (ValidateUtils.isNullOrEmpt(grabDoc.get(filedName))) {
                        keysIt.remove();
                    }
                }

                // 需要将 text 字段一并处理
                for (String filedExp : SegmentFieldsConfig.NED_SEGMENTE_FIELDS) {
                    NLPDataFormater.converStruct4SegFieldWrap(filedExp, grabDoc);
                }
                for (String filedExp : SegmentFieldsConfig.NED_SEGMENTE_FIELDS_TEXT) {
                    NLPDataFormater.converStruct4SegFieldWrap(filedExp, grabDoc);
                }

                //补全contentType 字段
                NLPDataFormater.supplementContentType(grabDoc);

                //遍历需要分词的字段列表，如果该字段需要分词，则执行分词
                for (int tfIdx = 0; tfIdx < SegmentFieldsConfig.NED_SEGMENTE_FIELDS_TEXT.length;
                     tfIdx++) {
                    String textFieldName = SegmentFieldsConfig.NED_SEGMENTE_FIELDS_TEXT[tfIdx];
                    if (!ValidateUtils.isNullOrEmpt(grabDoc, textFieldName)) {
                        if (SegmentFieldsConfig.PARAGRAPH_FILEDS_SETS.contains(textFieldName)) {
                            JSONArray filedValue = grabDoc.getJSONArray(textFieldName);
                            //分段的字段需要循环所有的段落的值
                            for (int i = 0; i < filedValue.length(); i++) {
                                NLPDataFormater.addSegmentInfo4Text(filedTermsMap, textFieldName, i, grabDoc, result, dictPlan, currentHost, currentPort);
                            }
                        } else {
                            //不是分段的字段
                            NLPDataFormater.addSegmentInfo4Text(filedTermsMap, textFieldName, -1, grabDoc, result, dictPlan, currentHost, currentPort);
                        }
                    }
                }

                //循环所有通用的需要分词的字段
                for (String tokenFiledExp : SegmentFieldsConfig.NED_SEGMENTE_FIELDS) {
                    if (NLPDataFormater.isExistFieldWrap(tokenFiledExp, grabDoc)) {
                        NLPDataFormater.addSegmentInfo4CommonWrap(filedTermsMap, grabDoc, result, tokenFiledExp, dictPlan, currentHost, currentPort);
                    } else {
                        //logger.debug("current filed:[" + tokenFiledExp + "] not exit in doc!");
                    }
                }

                transferParagraphFields(grabDoc);

                segResult = HttpUtils.executePost(result.toString(), "utf8", serverConfig.getNLPServerReqURL(), serverConfig.getNlpReqTimeOut(), HttpUtils.CONTENT_TYPE_TEXT_XML);
                responseData = new JSONObject(segResult);
                if (responseData.isNull(SegmentHelper.SEG_RESPONSE_BODY_KEY)) {
                    throw new RuntimeException("分词失败,应答格式错误:[" + segResult + "].");
                }
                resultDatas = responseData.getJSONArray(SegmentHelper.SEG_RESPONSE_BODY_KEY);
                if (result.length() != resultDatas.length()) {
                    throw new RuntimeException("segment doc failed! filed length not equest result length!");
                }

                //1.处理 正文字段
                String fiendName = null;
                //分词字段的值是一个JonsObject结构
                JSONObject analysisResult = null;
                for (int tfIdx = 0; tfIdx < SegmentFieldsConfig.NED_SEGMENTE_FIELDS_TEXT.length; tfIdx++) {
                    fiendName = SegmentFieldsConfig.NED_SEGMENTE_FIELDS_TEXT[tfIdx];

                    if (filedTermsMap.containsKey(fiendName)) {
                        Object textIdx = filedTermsMap.get(fiendName);
                        if (textIdx instanceof Integer) {
                            handleSegResult4TextField(fiendName, (Integer) textIdx, resultDatas, grabDoc, result);
                        } else if (textIdx instanceof ArrayList) {
                            JSONArray filedValues = grabDoc.getJSONArray(fiendName);
                            JSONObject curValue = null;
                            int curSegResultIdx = -1;
                            for (int i = 0; i < ((ArrayList) textIdx).size(); i++) {
                                curValue = (JSONObject) filedValues.get(i);
                                curSegResultIdx = ((ArrayList<Integer>) textIdx).get(i);
                                handleSegResult4TextField(fiendName, curSegResultIdx, resultDatas, curValue, result);
                            }
                        }
                    }
                }

                //2.处理通用字段
                for (String tokenFiledExp : SegmentFieldsConfig.NED_SEGMENTE_FIELDS) {
                    //content 存在 时候 才进行分词
                    if (NLPDataFormater.isExistFieldWrap(tokenFiledExp + "." + SegmentFieldsConfig.CONTENT_NAME_FOR_SEG_FIELD, grabDoc)) {
                        int textIdx = (Integer) filedTermsMap.get(tokenFiledExp);
                        analysisResult = (JSONObject) resultDatas.get(textIdx);
                        SegmentHelper.setTerms4CommonFieldWrap(tokenFiledExp, grabDoc, analysisResult);
                    }
                }

                //删除临时数据
                for (String rmField : SegmentFieldsConfig.REMOVED_FIELD) {
                    if (grabDoc.isNull(rmField)) {
                        continue;
                    }
                    grabDoc.remove(rmField);
                }
                //log the result doc
                if (logger.isDebugEnabled()) {
                    logger.debug("Job:[SegmenteJob] --+-doSegmentFromNLP for doc success doc:[" + grabDoc.toString() + "].");
                }
                logger.info("Job:[SegmenteJob] --+-doSegmentFromNLP for doc in [" + this.cacheName + "] success docSize:[" + grabDoc.toString().getBytes(Charset.forName("utf8")).length + "].");
            }

            long endTime = System.currentTimeMillis();

            //commonData.put("jsonDocStr", taskDataJson.toString());
            logger.info("Job:[SegmenteJob] --+-doSegmentFromNLP for taskdata in:[" + this.cacheName + "] success. Spend:[" + (endTime - startTime) + "] ms.");
        } catch (Exception e) {
            e.printStackTrace();
            //logger.info("Job:[SegmenteJob] run exception. ErrorMsg:[" + e.getMessage() + "] handleDocNum:[" + handleDocNum + "].");
            throw new RuntimeException("Job:[SegmenteJob] run exception. ErrorMsg:[" + e.getMessage() + "] URL:[" + serverConfig.getNLPServerReqURL() + "].", e);
        } finally {
        }
        return taskDataJson;
    }


    private static void handleSegResult4TextField(String fiendName, int textIdx, JSONArray resultDatas, final JSONObject targerDoc, final JSONArray segReqDatas) throws JSONException {
        boolean isRepost = false;
        if (textIdx < 0) {
            textIdx = -(textIdx) - 1;
            isRepost = true;
        }
        // 根据下标获取 分词结果
        JSONObject analysisResult = (JSONObject) resultDatas.get(textIdx);

        //判断当前字段是否为 分段字段
        if (NLPDataFormater.isExistFieldWrap(fiendName + "." + SegmentFieldsConfig.CONTENT_NAME_FOR_SEG_FIELD, targerDoc)) {
            SegmentHelper.setTerms4Text(fiendName, targerDoc, analysisResult);
        }

        //将分词结果中这这些字段设置到文章中
        for (String textFiledName : SegmentFieldsConfig.TEXT_FIELD_IN_SEG) {
            if (SegmentFieldsConfig.isTextField(textFiledName)) {
                // content 不为空的字段 就会分词 结果中包含terms
                if (NLPDataFormater.isExistFieldWrap(textFiledName + "." + SegmentFieldsConfig.CONTENT_NAME_FOR_SEG_FIELD, targerDoc)) {
                    SegmentHelper.setTerms4Text(textFiledName, targerDoc, analysisResult);
                }
            } else {
                SegmentHelper.setTerms4TextEmotionField(textFiledName, targerDoc, analysisResult);
            }
        }

        if (isRepost) {
            JSONObject analysisAncestorValue = (JSONObject) resultDatas.get(textIdx + 1);
            JSONObject requestData = (JSONObject) segReqDatas.get(textIdx + 1);

            JSONObject ancestorValue = new JSONObject();
            ancestorValue.put(SegmentFieldsConfig.CONTENT_NAME_FOR_SEG_FIELD, requestData.get(SegmentFieldsConfig.CONTENT_NAME_FOR_SEG_FIELD));
            targerDoc.put("ancestor_text", ancestorValue);


            final String ancestorPrefix = "ancestor_";
            String ancestorFieldname = null;
            for (String textFiledName : SegmentFieldsConfig.TEXT_FIELD_IN_SEG) {
                ancestorFieldname = ancestorPrefix + textFiledName;
                if (textFiledName.equals("text")) {
                    SegmentHelper.setTerms4Text(ancestorFieldname, targerDoc, analysisAncestorValue);
                } else {
                    SegmentHelper.setTerms4CommonFieldWrap(ancestorFieldname, targerDoc, analysisAncestorValue);
                }
            }
        }
    }

    /**
     * 将需要分段的字段进行数据格式转化<BR>
     * <p>
     * 在进行分词时候，将该结构提交到PHP中，PHP中将该结构的数据(分词结果以及其他一些业务字段)按照分段后的格式进行直接赋值
     * <p>
     * doc
     * **+--pg_text
     * ********+--JSonArray
     * **************+--{"content":"xxx"}
     * **************+--{"content":"aaa"}
     * **************+--{"content":"bbb"}
     * <p>
     * TODO 上面的结构是在分词前，进行格式话分词字段时候将需要分词的字段转化成了上面没的结构{@link NLPDataFormater#converStruct4SegField(String, JSONObject)}<BR>
     * 这时因为，在数据入库mongo之前，PHP已经将html标签、图片、分段等进行了处理，已经将分段的字段转化成了上述的结构，后期如果不经过PHP的话可以不调用该方法再次<BR>
     * 对分段字段的结构进行转化<BR>
     * <p>
     * <p>
     * 该方法转化后的结构如下：
     * doc
     * **+--pg_text
     * ********+--JSonArray
     * **************+--[0]:JsonObject
     * ************************+--pg_text:JsonObject
     * ***************************************+--content:ab
     * ***************************************+--terms:["a","b"]
     * ************************+--NRN:JsonObject
     * ************************+--emoNRN:JsonObject
     * ************************+--....
     * <p>
     * **************+--[1]:JsonObject
     * ************************+--pg_text:JsonObject
     * ***************************************+--content:ab
     * ***************************************+--terms:["a","b"]
     * ************************+--NRN:JsonObject
     * ************************+--emoNRN:JsonObject
     * ************************+--....
     * <p>
     * <p>
     * 该结构将直接存储到PHP中，调用PHP的addWeibo方法，进行入库，并将isSegement参数设置成为true,表示入库的数据已经分词完成<BR>
     * 在PHP中将会使用新的段落补充逻辑，将上述的结构设置到新的段落中
     *
     * @param grabDoc
     */
    private static void transferParagraphFields(final JSONObject grabDoc) throws JSONException {
        for (int i = 0; i < SegmentFieldsConfig.PARAGRAPH_FILEDS.length; i++) {
            if (ValidateUtils.isNullOrEmpt(grabDoc, SegmentFieldsConfig.PARAGRAPH_FILEDS[i])) {
                continue;
            }
            NLPDataFormater.transferParagraphFieldBy(SegmentFieldsConfig.PARAGRAPH_FILEDS[i], grabDoc);
        }
    }
}
