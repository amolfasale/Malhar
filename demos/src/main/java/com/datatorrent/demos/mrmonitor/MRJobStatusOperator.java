/*
 * Copyright (c) 2013 DataTorrent, Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.demos.mrmonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.annotation.ShipContainingJars;
import com.datatorrent.api.DefaultInputPort;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.api.IdleTimeHandler;
import com.datatorrent.api.Operator;
import com.datatorrent.demos.mrmonitor.MRStatusObject.TaskObject;

/**
 * <p>
 * MRJobStatusOperator class.
 * </p>
 *
 * @since 0.3.4
 */

@ShipContainingJars(classes = { org.apache.http.client.ClientProtocolException.class, org.apache.http.HttpRequest.class })
public class MRJobStatusOperator implements Operator, IdleTimeHandler
{
  private static final Logger LOG = LoggerFactory.getLogger(MRJobStatusOperator.class);

  private int sleepTime = 100;
  public int getSleepTime()
  {
    return sleepTime;
  }

  public void setSleepTime(int sleepTime)
  {
    this.sleepTime = sleepTime;
  }

  /*
   * each input string is a map of the following format
   * {"app_id":<>,"hadoop_version":<>,"api_version":<>,"command":<>,
   * "hostname":<>,"hs_port":<>,"rm_port":<>,"job_id":<>}
   */
  public final transient DefaultInputPort<MRStatusObject> input = new DefaultInputPort<MRStatusObject>() {
    @Override
    public void process(MRStatusObject mrStatusObj)
    {

      if (jobMap == null) {
        jobMap = new HashMap<String, MRStatusObject>();
      }

      if (jobMap.size() >= maxMapSize) {
        return;
      }

      if ("delete".equalsIgnoreCase(mrStatusObj.getCommand())) {
        removeJob(mrStatusObj.getJobId());
        return;
      }
      if ("clear".equalsIgnoreCase(mrStatusObj.getCommand())) {
        clearMap();
        return;
      }

      if (jobMap.get(mrStatusObj.getJobId()) != null) {
        mrStatusObj = jobMap.get(mrStatusObj.getJobId());
        output.emit(mrStatusObj.getJsonObject().toString());
        return;
      }

      if (mrStatusObj.getHadoopVersion() == 2) {
        getJsonForJob(mrStatusObj);
      } else if (mrStatusObj.getHadoopVersion() == 1) {
        getJsonForLegacyJob(mrStatusObj);
      }
      
      iterator = jobMap.values().iterator();
    }
  };

  private Map<String, MRStatusObject> jobMap = new HashMap<String, MRStatusObject>();
  private int maxMapSize = Constants.MAX_MAP_SIZE;

  public final transient DefaultOutputPort<String> output = new DefaultOutputPort<String>();
  public final transient DefaultOutputPort<String> mapOutput = new DefaultOutputPort<String>();
  public final transient DefaultOutputPort<String> reduceOutput = new DefaultOutputPort<String>();

  /**
   * This method gets the latest status of the job from the Resource Manager for jobs submitted on hadoop 2.x version
   * @param statusObj
   */
  private void getJsonForJob(MRStatusObject statusObj)
  {

    String url = "http://" + statusObj.getUri() + ":" + statusObj.getRmPort() + "/proxy/application_" + statusObj.getAppId() + "/ws/v1/mapreduce/jobs/job_" + statusObj.getJobId();
    String responseBody = Util.getJsonForURL(url);

    JSONObject jsonObj = Util.getJsonObject(responseBody);

    if (jsonObj == null) {
      url = "http://" + statusObj.getUri() + ":" + statusObj.getHistoryServerPort() + "/ws/v1/history/mapreduce/jobs/job_" + statusObj.getJobId();
      responseBody = Util.getJsonForURL(url);
      jsonObj = Util.getJsonObject(responseBody);
    }

    if (jsonObj != null) {
      if (jobMap.get(statusObj.getJobId()) != null) {
        MRStatusObject tempObj = jobMap.get(statusObj.getJobId());
        if (tempObj.getJsonObject().toString().equals(jsonObj.toString())) {
          getJsonsForTasks(statusObj);
          //output.emit(jsonObj.toString());
          //removeJob(statusObj.getJobId());
          return;
        }
        // statusObj = tempObj;
      }

      //output.emit(jsonObj.toString());
      statusObj.setModified(true);
      statusObj.setJsonObject(jsonObj);
      getJsonsForTasks(statusObj);
      jobMap.put(statusObj.getJobId(), statusObj);
      

    }
  }

  /**
   * This method gets the latest status of the tasks for a job from the Resource Manager for jobs submitted on hadoop 2.x version
   * @param statusObj
   */
  private void getJsonsForTasks(MRStatusObject statusObj)
  {
    String url = "http://" + statusObj.getUri() + ":" + statusObj.getRmPort() + "/proxy/application_" + statusObj.getAppId() + "/ws/v1/mapreduce/jobs/job_" + statusObj.getJobId() + "/tasks/";
    String responseBody = Util.getJsonForURL(url);

    JSONObject jsonObj = Util.getJsonObject(responseBody);
    if (jsonObj == null) {
      url = "http://" + statusObj.getUri() + ":" + statusObj.getHistoryServerPort() + "/ws/v1/history/mapreduce/jobs/job_" + statusObj.getJobId() + "/tasks/";
      responseBody = Util.getJsonForURL(url);

      jsonObj = Util.getJsonObject(responseBody);
    }

    if (jsonObj != null) {

      try {
        Map<String, TaskObject> mapTaskOject = statusObj.getMapJsonObject();
        Map<String, TaskObject> reduceTaskOject = statusObj.getReduceJsonObject();
        JSONArray taskJsonArray = jsonObj.getJSONObject("tasks").getJSONArray("task");

        for (int i = 0; i < taskJsonArray.length(); i++) {
          JSONObject taskObj = taskJsonArray.getJSONObject(i);
          if (Constants.REDUCE_TASK_TYPE.equalsIgnoreCase(taskObj.getString(Constants.TASK_TYPE))) {
            if (reduceTaskOject.get(taskObj.getString(Constants.TASK_ID)) != null) {
              TaskObject tempTaskObj = reduceTaskOject.get(taskObj.getString(Constants.TASK_ID));              
              if(tempTaskObj.getJsonString().equals(taskObj.toString())){               
                //tempTaskObj.setModified(false);
                //reduceTaskOject.put(taskObj.getString(Constants.TASK_ID), tempTaskObj);
                continue;
              }
              tempTaskObj.setJson(taskObj);
              tempTaskObj.setModified(true);
              reduceTaskOject.put(taskObj.getString(Constants.TASK_ID), tempTaskObj);
              continue;
            }
            //reduceOutput.emit(taskObj.toString());
            reduceTaskOject.put(taskObj.getString(Constants.TASK_ID), new TaskObject(taskObj));
          } else {
            if (mapTaskOject.get(taskObj.getString(Constants.TASK_ID)) != null) {
              TaskObject tempTaskObj = mapTaskOject.get(taskObj.getString(Constants.TASK_ID));              
              if(tempTaskObj.getJsonString().equals(taskObj.toString())){               
                //tempTaskObj.setModified(false);
                //mapTaskOject.put(taskObj.getString(Constants.TASK_ID), tempTaskObj);
                continue;
              }
              tempTaskObj.setJson(taskObj);
              tempTaskObj.setModified(true);
              mapTaskOject.put(taskObj.getString(Constants.TASK_ID), tempTaskObj);
              continue;
            }
            //mapOutput.emit(taskObj.toString());
            mapTaskOject.put(taskObj.getString(Constants.TASK_ID), new TaskObject(taskObj));
          }
        }
        statusObj.setMapJsonObject(mapTaskOject);
        statusObj.setReduceJsonObject(reduceTaskOject);
      } catch (Exception e) {
        LOG.info("exception: {}",e.getMessage());
      }
    }

  }

  /**
   * This method gets the latest status of the job from the Task Manager for jobs submitted on hadoop 1.x version
   * @param statusObj
   */
  private void getJsonForLegacyJob(MRStatusObject statusObj)
  {

    String url = "http://" + statusObj.getUri() + ":" + statusObj.getRmPort() + "/jobdetails.jsp?format=json&jobid=job_" + statusObj.getJobId();
    String responseBody = Util.getJsonForURL(url);

    JSONObject jsonObj = Util.getJsonObject(responseBody);
    if (jsonObj == null)
      return;

    if (jobMap.get(statusObj.getJobId()) != null) {
      MRStatusObject tempObj = jobMap.get(statusObj.getJobId());
      if (tempObj.getJsonObject().toString().equals(jsonObj.toString())){
        getJsonsForLegacyTasks(statusObj, "map");
        getJsonsForLegacyTasks(statusObj, "reduce");
//        output.emit(jsonObj.toString());
//        removeJob(statusObj.getJobId());
        return;
      }
    }

    //output.emit(jsonObj.toString());
    statusObj.setModified(true);
    statusObj.setJsonObject(jsonObj);
    getJsonsForLegacyTasks(statusObj, "map");
    getJsonsForLegacyTasks(statusObj, "reduce");
    jobMap.put(statusObj.getJobId(), statusObj);

  }

  /**
   * This method gets the latest status of the tasks for a job from the Task Manager for jobs submitted on hadoop 1.x version
   * @param statusObj
   * @param type
   */
  private void getJsonsForLegacyTasks(MRStatusObject statusObj, String type)
  {
    try {
      JSONObject jobJson = statusObj.getJsonObject();
      int totalTasks = ((JSONObject) ((JSONObject) jobJson.get(type + "TaskSummary")).get("taskStats")).getInt("numTotalTasks");
      Map<String, TaskObject> taskMap;
      if (type.equalsIgnoreCase("map"))
        taskMap = statusObj.getMapJsonObject();
      else
        taskMap = statusObj.getReduceJsonObject();

      int totalPagenums = (totalTasks / Constants.MAX_TASKS) + 1;
      String baseUrl = "http://" + statusObj.getUri() + ":" + statusObj.getRmPort() + "/jobtasks.jsp?type=" + type + "&format=json&jobid=job_" + statusObj.getJobId() + "&pagenum=";

      for (int pagenum = 1; pagenum <= totalPagenums; pagenum++) {

        String url = baseUrl + pagenum;
        String responseBody = Util.getJsonForURL(url);

        JSONObject jsonObj = Util.getJsonObject(responseBody);
        if (jsonObj == null)
          return;

        JSONArray taskJsonArray = jsonObj.getJSONArray("tasksInfo");

        for (int i = 0; i < taskJsonArray.length(); i++) {
          JSONObject taskObj = taskJsonArray.getJSONObject(i);
          {
            if (taskMap.get(taskObj.getString(Constants.LEAGACY_TASK_ID)) != null) {
              TaskObject tempReduceObj = taskMap.get(taskObj.getString(Constants.LEAGACY_TASK_ID));
              if(tempReduceObj.getJsonString().equals(taskObj.toString())){               
                //tempReduceObj.setModified(false);
                //taskMap.put(taskObj.getString(Constants.TASK_ID), tempReduceObj);
                continue;
              }
              tempReduceObj.setJson(taskObj);
              tempReduceObj.setModified(true);
              taskMap.put(taskObj.getString(Constants.TASK_ID), tempReduceObj);
              continue;
              
            }
            taskMap.put(taskObj.getString(Constants.LEAGACY_TASK_ID), new TaskObject(taskObj));
          }
        }
      }

      if (type.equalsIgnoreCase("map"))
        statusObj.setMapJsonObject(taskMap);
      else
        statusObj.setReduceJsonObject(taskMap);
    } catch (Exception e) {
      LOG.info(e.getMessage());
    }

  }

  private transient Iterator<MRStatusObject> iterator;

  @Override
  public void handleIdleTime()
  {
    try{
      Thread.sleep(sleepTime);//
    }
    catch(InterruptedException ie){
    //If this thread was intrrupted by nother thread
    }
    if (!iterator.hasNext()) {
      iterator = jobMap.values().iterator();
    }

    if (iterator.hasNext()) {
      MRStatusObject obj = iterator.next();
      if (obj.getHadoopVersion() == 2)
        getJsonForJob(obj);
      else if (obj.getHadoopVersion() == 1)
        getJsonForLegacyJob(obj);
    }
  }

  @Override
  public void setup(OperatorContext arg0)
  {
    iterator = jobMap.values().iterator();
  }

  @Override
  public void teardown()
  {
  }

  @Override
  public void beginWindow(long arg0)
  {
  }

  @Override
  public void endWindow()
  {
    List<String> delList = new ArrayList<String>();
    try {
      for (Map.Entry<String, MRStatusObject> entry : jobMap.entrySet()) {

        boolean modified = false;
        MRStatusObject obj = entry.getValue();
        if (obj.isModified()) {
          modified = true;
          obj.setModified(false);
          output.emit(obj.getJsonObject().toString());
        }
        JSONObject outputJsonObject = new JSONObject();
        outputJsonObject.put("id", obj.getJobId());
        JSONArray arr = new JSONArray();        
        
        for (Map.Entry<String, TaskObject> mapEntry : obj.getMapJsonObject().entrySet()) {
          TaskObject json = mapEntry.getValue();
          if (json.isModified()) {
            modified = true;
            json.setModified(false);
            arr.put(json.getJson());            
          }
        }
        
        if(arr.length() > 0){
          outputJsonObject.put("tasks", arr);
          mapOutput.emit(outputJsonObject.toString());
        }
        
        outputJsonObject = new JSONObject();
        outputJsonObject.put("id", obj.getJobId());
        arr = new JSONArray(); 
        
        for (Map.Entry<String, TaskObject> mapEntry : obj.getReduceJsonObject().entrySet()) {
          TaskObject json = mapEntry.getValue();
          if (json.isModified()) {
            modified = true;
            json.setModified(false);
            arr.put(json.getJson());
          }
        }
        
        
        if(arr.length() > 0){
          outputJsonObject.put("tasks", arr);
          reduceOutput.emit(outputJsonObject.toString());
        }
        
        if (!modified) {
          delList.add(obj.getJobId());
        }
      }
    } catch (Exception ex) {
      LOG.warn("error creating json {}", ex.getMessage());
    }
    /*
    if (!delList.isEmpty()) {
      Iterator<String> itr = delList.iterator();
      while (itr.hasNext()) {
        removeJob(itr.next());
      }
    }
    */
  }

  /**
   * This method removes the job from the map
   * @param jobId
   */
  public void removeJob(String jobId)
  {
    if (jobMap != null) {
      jobMap.remove(jobId);
      iterator = jobMap.values().iterator();
    }
  }

  /**
   * This method clears the job map 
   */
  public void clearMap()
  {
    if (jobMap != null) {
      jobMap.clear();
      iterator = jobMap.values().iterator();
    }
  }

  public int getMaxMapSize()
  {
    return maxMapSize;
  }

  public void setMaxMapSize(int maxMapSize)
  {
    this.maxMapSize = maxMapSize;
  }

}
