/*
 * JBoss, Home of Professional Open Source
 * Copyright 2006, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jdiameter.client.impl.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jdiameter.api.Answer;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.InternalException;
import org.jdiameter.client.api.IEventListener;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.api.controller.IPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a Diameter message.
 * 
 * @author erick.svenson@yahoo.com
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 * @author <a href="mailto:baranowb@gmail.com"> Bartosz Baranowski </a>
 */
public class MessageImpl implements IMessage {

  private static final long serialVersionUID = 1L;

  private static final Logger logger = LoggerFactory.getLogger(MessageImpl.class);
  private static final MessageParser parser = new MessageParser();
  int state = STATE_NOT_SENT;

  short version = 1, flags;
  int commandCode;
  long applicationId;
  long hopByHopId;
  boolean notMutableHopByHop;
  long endToEndId;

  AvpSetImpl avpSet;

  boolean isNetworkRequest = false;

  transient IPeer peer;
  transient TimerTask timerTask;
  transient IEventListener listener;

  // Cached result for getApplicationIdAvps() method. It is called extensively and takes some time.
  // Potential place for dirt, but Application IDs don't change during message life time.
  transient List<ApplicationId> applicationIds;

  /**
   * Create empty message
   * 
   * @param parser
   * @param commandCode
   * @param appId
   */
  MessageImpl(int commandCode, long appId) {
    this.commandCode = commandCode;
    this.applicationId = appId;

    this.avpSet = new AvpSetImpl();
    this.endToEndId = parser.getNextEndToEndId();
  }

  /**
   * Create empty message
   * 
   * @param parser
   * @param commandCode
   * @param applicationId
   * @param flags
   * @param hopByHopId
   * @param endToEndId
   * @param avpSet
   */
  MessageImpl(int commandCode, long applicationId, short flags, long hopByHopId, long endToEndId, AvpSetImpl avpSet) {
    this(commandCode, applicationId);
    this.flags = flags;
    this.hopByHopId = hopByHopId;
    this.endToEndId = endToEndId;
    if (avpSet != null) {
      this.avpSet = avpSet;
    }
  }

  //  /**
  //   * Create empty message
  //   * 
  //   * @param metaData
  //   * @param parser
  //   * @param commandCode
  //   * @param appId
  //   */
  //  MessageImpl(MetaData metaData, MessageParser parser, int commandCode, long appId) {
  //    this(commandCode, appId);
  //    try {
  //      getAvps().addAvp(Avp.ORIGIN_HOST, metaData.getLocalPeer().getUri().getFQDN(), true, false, true);
  //      getAvps().addAvp(Avp.ORIGIN_REALM, metaData.getLocalPeer().getRealmName(), true, false, true);
  //    }
  //    catch (Exception e) {
  //      logger.debug("Can not create message", e);
  //    }
  //  }

  /**
   * Create Answer
   * 
   * @param request parent request
   */
  private MessageImpl(MessageImpl request) {
    this(request.getCommandCode(), request.getHeaderApplicationId());
    copyHeader(request);
    setRequest(false);
    parser.copyBasicAvps(this, request, true);
  }

  public byte getVersion() {
    return (byte) version;
  }

  public boolean isRequest() {
    return (flags & 0x80) != 0;
  }

  public void setRequest(boolean b) {
    if (b) {
      flags |= 0x80;
    }
    else {
      flags &= 0x7F;
    }
  }

  public boolean isProxiable() {
    return (flags & 0x40) != 0;
  }

  public void setProxiable(boolean b) {
    if (b) {
      flags |= 0x40;
    }
    else {
      flags &= 0xBF;
    }
  }

  public boolean isError() {
    return (flags & 0x20) != 0;
  }

  public void setError(boolean b) {
    if (b) {
      flags |= 0x20;
    }
    else {
      flags &= 0xDF;
    }
  }

  public boolean isReTransmitted() {
    return (flags & 0x10) != 0;
  }

  public void setReTransmitted(boolean b) {
    if (b) {
      flags |= 0x10;
    }
    else {
      flags &= 0xEF;
    }
  }

  public int getCommandCode() {
    return this.commandCode;
  }

  public String getSessionId() {
    try {
      Avp avpSessionId = avpSet.getAvp(Avp.SESSION_ID);
      return avpSessionId != null ? avpSessionId.getUTF8String() : null;
    }
    catch (AvpDataException ade) {
      logger.error("Failed to fetch Session-Id", ade);
      return null;
    }
  }

  public Answer createAnswer() {
    MessageImpl answer = new MessageImpl(this);
    return answer;
  }

  public Answer createAnswer(long resultCode) {
    MessageImpl answer = new MessageImpl(this);
    try {
      answer.getAvps().addAvp(Avp.RESULT_CODE, resultCode, true, false, true);
    }
    catch (Exception e) {
      logger.debug("Can not create answer message", e);
    }
    //Its set in constructor.
    //answer.setRequest(false);
    return answer;
  }

  public Answer createAnswer(long vendorId, long experimentalResultCode) {
    MessageImpl answer = new MessageImpl(this);
    try {
      AvpSet exp_code = answer.getAvps().addGroupedAvp(297, true, false);
      exp_code.addAvp(Avp.VENDOR_ID, vendorId, true, false, true);
      exp_code.addAvp(Avp.EXPERIMENTAL_RESULT_CODE, experimentalResultCode, true, false, true);
    }
    catch (Exception e) {
      logger.debug("Can not create answer message", e);
    }
    answer.setRequest(false);
    return answer;
  }

  public long getApplicationId() {
    return applicationId;
  }

  public ApplicationId getSingleApplicationId() {
    return getSingleApplicationId(this.applicationId);
  }

  public List<ApplicationId> getApplicationIdAvps() {
    if (this.applicationIds != null) {
      return this.applicationIds;
    }

    List<ApplicationId> rc = new ArrayList<ApplicationId>();
    try {
      AvpSet authAppId = avpSet.getAvps(Avp.AUTH_APPLICATION_ID);
      for (Avp anAuthAppId : authAppId) {
        rc.add(ApplicationId.createByAuthAppId((anAuthAppId).getInteger32()));
      }
      AvpSet accAppId = avpSet.getAvps(Avp.ACCT_APPLICATION_ID);
      for (Avp anAccAppId : accAppId) {
        rc.add(ApplicationId.createByAccAppId((anAccAppId).getInteger32()));
      }
      AvpSet specAppId = avpSet.getAvps(Avp.VENDOR_SPECIFIC_APPLICATION_ID);
      for (Avp aSpecAppId : specAppId) {
        long vendorId = 0, acctApplicationId = 0, authApplicationId = 0;
        AvpSet avps = (aSpecAppId).getGrouped();
        for (Avp localAvp : avps) {
          if (localAvp.getCode() == Avp.VENDOR_ID) {
            vendorId = localAvp.getUnsigned32();
          }
          if (localAvp.getCode() == Avp.AUTH_APPLICATION_ID) {
            authApplicationId = localAvp.getUnsigned32();
          }
          if (localAvp.getCode() == Avp.ACCT_APPLICATION_ID) {
            acctApplicationId = localAvp.getUnsigned32();
          }

          if (authApplicationId != 0) {
            rc.add(ApplicationId.createByAuthAppId(vendorId, authApplicationId));
          }
          if (acctApplicationId != 0) {
            rc.add(ApplicationId.createByAccAppId(vendorId, acctApplicationId));
          }
        }
      }
    }
    catch (Exception exception) {
      return new ArrayList<ApplicationId>();
    }

    this.applicationIds = rc;
    return this.applicationIds;
  }

  public ApplicationId getSingleApplicationId(long applicationId) {
    logger.debug("In getSingleApplicationId for application id [{}]", applicationId);
    List<ApplicationId> appIds = getApplicationIdAvps();
    logger.debug("Application Ids in this message are:");
    ApplicationId firstOverall = null;
    ApplicationId firstWithZeroVendor = null;
    ApplicationId firstWithNonZeroVendor = null;
    for (ApplicationId id : appIds) {
      logger.debug("[{}]", id);
      if (firstOverall == null) {
        firstOverall = id;
      }
      if (applicationId != 0) {
        if (firstWithZeroVendor == null && id.getVendorId() == 0 && (applicationId == id.getAuthAppId() || applicationId == id.getAcctAppId())) {
          firstWithZeroVendor = id;
        }
        if (firstWithNonZeroVendor == null && id.getVendorId() != 0 && (applicationId == id.getAuthAppId() || applicationId == id.getAcctAppId())) {
          firstWithNonZeroVendor = id;
          break;
        }
      }
    }
    ApplicationId toReturn = null;
    if (firstWithNonZeroVendor != null) {
      toReturn = firstWithNonZeroVendor;
      logger.debug("Returning [{}] as the first application id because its the first vendor specific one found", toReturn);
    }
    else if (firstWithZeroVendor != null) {
      toReturn = firstWithZeroVendor;
      logger.debug("Returning [{}] as the first application id because there are no vendor specific ones found", toReturn);
    }
    else {
      toReturn = firstOverall;
      logger.debug("Returning [{}] as the first application id because none with the requested app ids were found", toReturn);
    }

    if (toReturn == null) {
      // TODO: ammendonca: improve this (find vendor? use common app list map?)
      logger.debug("There are no Application-Id AVPs. Using the value in the header and assuming as Auth Application-Id [{}]", this.applicationId);
      toReturn = ApplicationId.createByAuthAppId(this.applicationId);
    }

    return toReturn;
  }

  public long getHopByHopIdentifier() {
    return hopByHopId;
  }

  public long getEndToEndIdentifier() {
    return endToEndId;
  }

  public AvpSet getAvps() {
    return avpSet;
  }

  protected void copyHeader(MessageImpl request) {
    endToEndId = request.endToEndId;
    hopByHopId = request.hopByHopId;
    version    = request.version;
    flags      = request.flags;
    peer       = request.peer;
  }

  public Avp getResultCode() {
    return getAvps().getAvp(Avp.RESULT_CODE);
  }

  public void setNetworkRequest(boolean isNetworkRequest) {
    this.isNetworkRequest = isNetworkRequest;
  }

  public boolean isNetworkRequest() {
    return isNetworkRequest;
  }

  public boolean isWrapperFor(Class<?> aClass) throws InternalException {
    return false;
  }

  public <T> T unwrap(Class<T> aClass) throws InternalException {
    return null;
  }

  // Inner API
  public void setHopByHopIdentifier(long hopByHopId) {
    if (hopByHopId < 0) {
      this.hopByHopId = -hopByHopId;
      this.notMutableHopByHop = true;
    }
    else {
      if (!this.notMutableHopByHop) {
        this.hopByHopId = hopByHopId;
      }
    }
  }

  public void setEndToEndIdentifier(long endByEndId) {
    this.endToEndId = endByEndId;
  }

  public IPeer getPeer() {
    return peer;
  }

  public void setPeer(IPeer peer) {
    this.peer = peer; 
  }

  public int getState() {
    return state;
  }

  public long getHeaderApplicationId() {
    return applicationId;
  }

  public void setHeaderApplicationId(long applicationId) {
    this.applicationId = applicationId;
  }

  public int getFlags() {
    return flags;
  }

  public void setState(int newState) {
    state = newState;
  }

  public void createTimer(ScheduledExecutorService scheduledFacility, long timeOut, TimeUnit timeUnit) {
    timerTask = new TimerTask(this);
    timerTask.setTimerHandler(scheduledFacility, scheduledFacility.schedule(timerTask, timeOut, timeUnit));
  }

  public void runTimer() {
    if (timerTask != null && !timerTask.isDone() && !timerTask.isCancelled()) {
      timerTask.run();
    }
  }

  public boolean isTimeOut() {
    return timerTask != null && timerTask.isDone() && !timerTask.isCancelled();
  }

  public void setListener(IEventListener listener) {
    this.listener = listener;
  }

  public IEventListener getEventListener() {
    return listener;
  }

  public void clearTimer() {
    if (timerTask != null) {
      timerTask.cancel();
    }
  }

  public String toString() {
    return "MessageImpl{" + "commandCode=" + commandCode + ", flags=" + flags + '}';
  }

  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    MessageImpl message = (MessageImpl) o;

    return applicationId == message.applicationId && commandCode == message.commandCode && 
    endToEndId == message.endToEndId && hopByHopId == message.hopByHopId;
  }

  public int hashCode() {
    long result;
    result = commandCode;
    result = 31 * result + applicationId;
    result = 31 * result + hopByHopId;
    result = 31 * result + endToEndId;
    return new Long(result).hashCode();
  }

  public String getDuplicationKey() {
    try {
      return getDuplicationKey(getAvps().getAvp(Avp.ORIGIN_HOST).getDiameterIdentity(), getEndToEndIdentifier());
    }
    catch (AvpDataException e) {
      throw new IllegalArgumentException(e);
    }
  }


  public String getDuplicationKey(String host, long endToEndId) {
    return host + endToEndId;
  }

  public Object clone() {
    try {
      return parser.createMessage(parser.encodeMessage(this));
    }
    catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }    

  protected static class TimerTask implements Runnable {

    ScheduledFuture timerHandler;
    MessageImpl message;
    ScheduledExecutorService scheduledFacility;

    public TimerTask(MessageImpl message) {
      this.message = message;
    }

    public void setTimerHandler(ScheduledExecutorService scheduledFacility, ScheduledFuture timerHandler) {
      this.scheduledFacility = scheduledFacility;
      this.timerHandler = timerHandler;
    }

    public void run() {
      try {
        if (message != null && message.state != STATE_ANSWERED) {
          IEventListener listener = null;
          if (message.listener  instanceof IEventListener) {
            listener = message.listener;
          }
          if (listener != null && listener.isValid()) {
            if (message.peer != null) {
              message.peer.remMessage(message);
            }
            message.listener.timeoutExpired(message);
          }
        }
      }
      catch(Throwable e) {
        logger.debug("Can not process timeout", e);
      }
    }

    public void cancel() {
      if (timerHandler != null) {
        timerHandler.cancel(true);
        if (scheduledFacility instanceof ThreadPoolExecutor && timerHandler instanceof Runnable) {
          ((ThreadPoolExecutor) scheduledFacility).remove((Runnable) timerHandler);
        }
      }
      message = null;
    }

    public boolean isDone() {
      return timerHandler != null && timerHandler.isDone();
    }

    public boolean isCancelled() {
      return timerHandler == null || timerHandler.isCancelled();
    }
  }
}
