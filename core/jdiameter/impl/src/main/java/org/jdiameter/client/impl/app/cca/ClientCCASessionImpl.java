 /*
  * TeleStax, Open Source Cloud Communications
  * Copyright 2011-2016, TeleStax Inc. and individual contributors
  * by the @authors tag.
  *
  * This program is free software: you can redistribute it and/or modify
  * under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation; either version 3 of
  * the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.
  *
  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>
  *
  * This file incorporates work covered by the following copyright and
  * permission notice:
  *
  *   JBoss, Home of Professional Open Source
  *   Copyright 2007-2011, Red Hat, Inc. and individual contributors
  *   by the @authors tag. See the copyright.txt in the distribution for a
  *   full listing of individual contributors.
  *
  *   This is free software; you can redistribute it and/or modify it
  *   under the terms of the GNU Lesser General Public License as
  *   published by the Free Software Foundation; either version 2.1 of
  *   the License, or (at your option) any later version.
  *
  *   This software is distributed in the hope that it will be useful,
  *   but WITHOUT ANY WARRANTY; without even the implied warranty of
  *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  *   Lesser General Public License for more details.
  *
  *   You should have received a copy of the GNU Lesser General Public
  *   License along with this software; if not, write to the Free
  *   Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
  *   02110-1301 USA, or see the FSF site: http://www.fsf.org.
  */

package org.jdiameter.client.impl.app.cca;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jdiameter.api.Answer;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.EventListener;
import org.jdiameter.api.IllegalDiameterStateException;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.Message;
import org.jdiameter.api.NetworkReqListener;
import org.jdiameter.api.OverloadException;
import org.jdiameter.api.Request;
import org.jdiameter.api.RouteException;
import org.jdiameter.api.app.AppAnswerEvent;
import org.jdiameter.api.app.AppEvent;
import org.jdiameter.api.app.AppSession;
import org.jdiameter.api.app.StateChangeListener;
import org.jdiameter.api.app.StateEvent;
import org.jdiameter.api.auth.events.ReAuthAnswer;
import org.jdiameter.api.auth.events.ReAuthRequest;
import org.jdiameter.api.cca.ClientCCASession;
import org.jdiameter.api.cca.ClientCCASessionListener;
import org.jdiameter.api.cca.events.JCreditControlAnswer;
import org.jdiameter.api.cca.events.JCreditControlRequest;
import org.jdiameter.client.api.ISessionFactory;
import org.jdiameter.client.impl.app.cca.Event.Type;
import org.jdiameter.common.api.app.IAppSessionState;
import org.jdiameter.common.api.app.cca.ClientCCASessionState;
import org.jdiameter.common.api.app.cca.ICCAMessageFactory;
import org.jdiameter.common.api.app.cca.IClientCCASessionContext;
import org.jdiameter.common.impl.app.AppAnswerEventImpl;
import org.jdiameter.common.impl.app.AppEventImpl;
import org.jdiameter.common.impl.app.AppRequestEventImpl;
import org.jdiameter.common.impl.app.cca.AppCCASessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client Credit-Control Application session implementation
 *
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 * @author <a href="mailto:baranowb@gmail.com"> Bartosz Baranowski </a>
 */
public class ClientCCASessionImpl extends AppCCASessionImpl implements ClientCCASession, NetworkReqListener, EventListener<Request, Answer> {

  private static final Logger logger = LoggerFactory.getLogger(ClientCCASessionImpl.class);

  // session data pojo, local reference so we dont have to cast super.data to IClientCCASessionData
  protected IClientCCASessionData sessionData;

  // Session State Handling ---------------------------------------------------

  protected Lock sendAndStateLock = new ReentrantLock();
  // Session Based Queue
  protected ArrayList<Event> eventQueue = new ArrayList<Event>(); //FIXME: this is not replicable?
  // Factories and Listeners --------------------------------------------------
  protected ICCAMessageFactory factory;
  protected ClientCCASessionListener listener;
  protected IClientCCASessionContext context;


  protected static final String TX_TIMER_NAME = "CCA_CLIENT_TX_TIMER";
  protected static final long TX_TIMER_DEFAULT_VALUE = 30 * 60 * 1000; // miliseconds


  protected long[] authAppIds = new long[] { 4 };

  protected static final int CCFH_TERMINATE = 0;
  protected static final int CCFH_CONTINUE = 1;
  protected static final int CCFH_RETRY_AND_TERMINATE = 2;

  private static final int DDFH_TERMINATE_OR_BUFFER = 0;
  private static final int DDFH_CONTINUE = 1;

  // CC-Request-Type Values ---------------------------------------------------
  private static final int DIRECT_DEBITING = 0;
  private static final int REFUND_ACCOUNT = 1;
  private static final int CHECK_BALANCE = 2;
  private static final int PRICE_ENQUIRY = 3;
  private static final int EVENT_REQUEST = 4;

  // Error Codes --------------------------------------------------------------
  private static final long END_USER_SERVICE_DENIED = 4010;
  private static final long CREDIT_CONTROL_NOT_APPLICABLE = 4011;
  private static final long USER_UNKNOWN = 5030;

  private static final long DIAMETER_UNABLE_TO_DELIVER = 3002L;
  private static final long DIAMETER_TOO_BUSY = 3004L;
  private static final long DIAMETER_LOOP_DETECTED = 3005L;

  protected static final Set<Long> temporaryErrorCodes;

  static {
    HashSet<Long> tmp = new HashSet<Long>();
    tmp.add(DIAMETER_UNABLE_TO_DELIVER);
    tmp.add(DIAMETER_TOO_BUSY);
    tmp.add(DIAMETER_LOOP_DETECTED);
    temporaryErrorCodes = Collections.unmodifiableSet(tmp);
  }

  public ClientCCASessionImpl(IClientCCASessionData data, ICCAMessageFactory fct, ISessionFactory sf, ClientCCASessionListener lst,
      IClientCCASessionContext ctx, StateChangeListener<AppSession> stLst) {
    super(sf, data);
    if (lst == null) {
      throw new IllegalArgumentException("Listener can not be null");
    }
    if (data == null) {
      throw new IllegalArgumentException("SessionData can not be null");
    }
    if (fct.getApplicationIds() == null) {
      throw new IllegalArgumentException("ApplicationId can not be less than zero");
    }

    this.sessionData = data;
    this.context = ctx;

    this.authAppIds = fct.getApplicationIds();
    this.listener = lst;
    this.factory = fct;
    super.addStateChangeNotification(stLst);

  }

  protected int getLocalCCFH() {
    return sessionData.getGatheredCCFH() >= 0 ? sessionData.getGatheredCCFH() : context.getDefaultCCFHValue();
  }

  protected int getLocalDDFH() {
    return sessionData.getGatheredDDFH() >= 0 ? sessionData.getGatheredDDFH() : context.getDefaultDDFHValue();
  }

  @Override
  public void sendCreditControlRequest(JCreditControlRequest request)
      throws InternalException, IllegalDiameterStateException, RouteException, OverloadException {
    extractFHAVPs(request, null);
    this.handleEvent(new Event(true, request, null));
  }

  @Override
  public void sendReAuthAnswer(ReAuthAnswer answer) throws InternalException, IllegalDiameterStateException, RouteException, OverloadException {
    this.handleEvent(new Event(Event.Type.SEND_RAA, null, answer));
  }

  @Override
  public boolean isStateless() {
    return false;
  }

  public boolean isEventBased() {
    return this.sessionData.isEventBased();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <E> E getState(Class<E> stateType) {
    return stateType == ClientCCASessionState.class ? (E) this.sessionData.getClientCCASessionState() : null;
  }

  @Override
  public boolean handleEvent(StateEvent event) throws InternalException, OverloadException {
    return this.isEventBased() ? handleEventForEventBased(event) : handleEventForSessionBased(event);
  }

  protected boolean handleEventForEventBased(StateEvent event) throws InternalException, OverloadException {
    try {
      sendAndStateLock.lock();
      final Event localEvent = (Event) event;
      final Event.Type eventType = (Type) localEvent.getType();
      final ClientCCASessionState state = this.sessionData.getClientCCASessionState();
      switch (state) {

        case IDLE:
          switch (eventType) {
            case SEND_EVENT_REQUEST:
              // Current State: IDLE
              // Event: Client or device requests a one-time service
              // Action: Send CC event request, start Tx
              // New State: PENDING_E
              startTx((JCreditControlRequest) localEvent.getRequest());
              setState(ClientCCASessionState.PENDING_EVENT);
              try {
                dispatchEvent(localEvent.getRequest());
              }
              catch (Exception e) {
                // This handles failure to send in PendingI state in FSM table
                logger.debug("Failure handling send event request", e);
                handleSendFailure(e, eventType, localEvent.getRequest().getMessage());
              }
              break;
            default:
              logger.warn("Wrong event type ({}) on state {}", eventType, state);
              break;
          }
          break;

        case PENDING_EVENT:
          switch (eventType) {
            case RECEIVE_EVENT_ANSWER:
              AppAnswerEvent answer = (AppAnswerEvent) localEvent.getAnswer();
              try {
                long resultCode = answer.getResultCodeAvp().getUnsigned32();
                if (isSuccess(resultCode)) {
                  // Current State: PENDING_E
                  // Event: Successful CC event answer received
                  // Action: Grant service to end user
                  // New State: IDLE
                  setState(ClientCCASessionState.IDLE, false);
                }
                if (isProvisional(resultCode) || isFailure(resultCode)) {
                  handleFailureMessage((JCreditControlAnswer) answer, (JCreditControlRequest) localEvent.getRequest(), eventType);
                }

                deliverCCAnswer((JCreditControlRequest) localEvent.getRequest(), (JCreditControlAnswer) localEvent.getAnswer());
              }
              catch (AvpDataException e) {
                logger.debug("Failure handling received answer event", e);
                setState(ClientCCASessionState.IDLE, false);
              }
              break;
            case Tx_TIMER_FIRED:
              handleTxExpires(localEvent.getRequest().getMessage());
              break;
            default:
              logger.warn("Wrong event type ({}) on state {}", eventType, state);
              break;
          }
          break;

        case PENDING_BUFFERED:
          switch (eventType) {
            case RECEIVE_EVENT_ANSWER:
              // Current State: PENDING_B
              // Event: Successful CC answer received
              // Action: Delete request
              // New State: IDLE
              setState(ClientCCASessionState.IDLE, false);
              sessionData.setBuffer(null);
              deliverCCAnswer((JCreditControlRequest) localEvent.getRequest(), (JCreditControlAnswer) localEvent.getAnswer());
              break;
            default:
              logger.warn("Wrong event type ({}) on state {}", eventType, state);
              break;
          }
          break;

        default:
          logger.warn("Wrong event type ({}) on state {}", eventType, state);
          break;
      }

      dispatch();
      return true;
    }
    catch (Exception e) {
      throw new InternalException(e);
    }
    finally {
      sendAndStateLock.unlock();
    }
  }

  protected boolean handleEventForSessionBased(StateEvent event) throws InternalException, OverloadException {
    try {
      sendAndStateLock.lock();
      Event localEvent = (Event) event;
      Event.Type eventType = (Type) localEvent.getType();
      ClientCCASessionState state = this.sessionData.getClientCCASessionState();
      switch (state) {

        case IDLE:
          switch (eventType) {
            case SEND_INITIAL_REQUEST:
              // Current State: IDLE
              // Event: Client or device requests access/service
              // Action: Send CC initial request, start Tx
              // New State: PENDING_I
              startTx((JCreditControlRequest) localEvent.getRequest());
              setState(ClientCCASessionState.PENDING_INITIAL);
              try {
                dispatchEvent(localEvent.getRequest());
              }
              catch (Exception e) {
                // This handles failure to send in PendingI state in FSM table
                handleSendFailure(e, eventType, localEvent.getRequest().getMessage());
              }
              break;
            default:
              logger.warn("Wrong event type ({}) on state {}", eventType, state);
              break;
          }
          break;

        case PENDING_INITIAL:
          AppAnswerEvent answer = (AppAnswerEvent) localEvent.getAnswer();
          switch (eventType) {
            case RECEIVED_INITIAL_ANSWER:
              long resultCode = answer.getResultCodeAvp().getUnsigned32();
              if (isSuccess(resultCode)) {
                // Current State: PENDING_I
                // Event: Successful CC initial answer received
                // Action: Stop Tx
                // New State: OPEN
                stopTx();
                setState(ClientCCASessionState.OPEN);
              }
              else if (isProvisional(resultCode) || isFailure(resultCode)) {
                handleFailureMessage((JCreditControlAnswer) answer, (JCreditControlRequest) localEvent.getRequest(), eventType);
              }
              deliverCCAnswer((JCreditControlRequest) localEvent.getRequest(), (JCreditControlAnswer) localEvent.getAnswer());
              break;
            case Tx_TIMER_FIRED:
              handleTxExpires(localEvent.getRequest().getMessage());
              break;
            case SEND_UPDATE_REQUEST:
            case SEND_TERMINATE_REQUEST:
              // Current State: PENDING_I
              // Event: User service terminated
              // Action: Queue termination event
              // New State: PENDING_I

              // Current State: PENDING_I
              // Event: Change in rating condition
              // Action: Queue changed rating condition event
              // New State: PENDING_I
              eventQueue.add(localEvent);
              break;
            default:
              logger.warn("Wrong event type ({}) on state {}", eventType, state);
              break;
          }
          break;

        case OPEN:
          switch (eventType) {
            case SEND_UPDATE_REQUEST:
              // Current State: OPEN
              // Event: Granted unit elapses and no final unit indication received
              // Action: Send CC update request, start Tx
              // New State: PENDING_U

              // Current State: OPEN
              // Event: Change in rating condition in queue
              // Action: Send CC update request, start Tx
              // New State: PENDING_U

              // Current State: OPEN
              // Event: Change in rating condition or Validity-Time elapses
              // Action: Send CC update request, start Tx
              // New State: PENDING_U

              // Current State: OPEN
              // Event: RAR received
              // Action: Send RAA followed by CC update request, start Tx
              // New State: PENDING_U
              startTx((JCreditControlRequest) localEvent.getRequest());
              setState(ClientCCASessionState.PENDING_UPDATE);
              try {
                dispatchEvent(localEvent.getRequest());
              }
              catch (Exception e) {
                // This handles failure to send in PendingI state in FSM table
                handleSendFailure(e, eventType, localEvent.getRequest().getMessage());
              }
              break;
            case SEND_TERMINATE_REQUEST:
              // Current State: OPEN
              // Event: Granted unit elapses and final unit action equal to TERMINATE received
              // Action: Terminate end user�s service, send CC termination request
              // New State: PENDING_T

              // Current State: OPEN
              // Event: Service terminated in queue
              // Action: Send CC termination request
              // New State: PENDING_T

              // Current State: OPEN
              // Event: User service terminated
              // Action: Send CC termination request
              // New State: PENDING_T
              setState(ClientCCASessionState.PENDING_TERMINATION);
              try {
                dispatchEvent(localEvent.getRequest());
              }
              catch (Exception e) {
                handleSendFailure(e, eventType, localEvent.getRequest().getMessage());
              }
              break;
            case RECEIVED_RAR:
              deliverRAR((ReAuthRequest) localEvent.getRequest());
              break;
            case SEND_RAA:
              try {
                dispatchEvent(localEvent.getAnswer());
              }
              catch (Exception e) {
                handleSendFailure(e, eventType, localEvent.getRequest().getMessage());
              }
              break;

            default:
              logger.warn("Wrong event type ({}) on state {}", eventType, state);
              break;
          }
          break;

        case PENDING_UPDATE:
          answer = (AppAnswerEvent) localEvent.getAnswer();
          switch (eventType) {
            case RECEIVED_UPDATE_ANSWER:
              long resultCode = answer.getResultCodeAvp().getUnsigned32();
              if (isSuccess(resultCode)) {
                // Current State: PENDING_U
                // Event: Successful CC update answer received
                // Action: Stop Tx
                // New State: OPEN
                stopTx();
                setState(ClientCCASessionState.OPEN);
              }
              else if (isProvisional(resultCode) || isFailure(resultCode)) {
                handleFailureMessage((JCreditControlAnswer) answer, (JCreditControlRequest) localEvent.getRequest(), eventType);
              }
              deliverCCAnswer((JCreditControlRequest) localEvent.getRequest(), (JCreditControlAnswer) localEvent.getAnswer());
              break;
            case Tx_TIMER_FIRED:
              handleTxExpires(localEvent.getRequest().getMessage());
              break;

            case SEND_UPDATE_REQUEST:
            case SEND_TERMINATE_REQUEST:
              // Current State: PENDING_U
              // Event: User service terminated
              // Action: Queue termination event
              // New State: PENDING_U

              // Current State: PENDING_U
              // Event: Change in rating condition
              // Action: Queue changed rating condition event
              // New State: PENDING_U
              eventQueue.add(localEvent);
              break;
            case RECEIVED_RAR:
              deliverRAR((ReAuthRequest) localEvent.getRequest());
              break;
            case SEND_RAA:
              // Current State: PENDING_U
              // Event: RAR received
              // Action: Send RAA
              // New State: PENDING_U
              try {
                dispatchEvent(localEvent.getAnswer());
              }
              catch (Exception e) {
                handleSendFailure(e, eventType, localEvent.getRequest().getMessage());
              }
              break;
          }

          break;

        case PENDING_TERMINATION:
          switch (eventType) {
            case SEND_UPDATE_REQUEST:
              try {
                // Current State: PENDING_T
                // Event: Change in rating condition
                // Action: -
                // New State: PENDING_T
                dispatchEvent(localEvent.getRequest());
                // No transition
              }
              catch (Exception e) {
                // This handles failure to send in PendingI state in FSM table
                // handleSendFailure(e, eventType);
              }
              break;
            case RECEIVED_TERMINATED_ANSWER:
              // Current State: PENDING_T
              // Event: Successful CC termination answer received
              // Action: -
              // New State: IDLE

              // Current State: PENDING_T
              // Event: Failure to send, temporary error, or failed answer
              // Action: -
              // New State: IDLE

              //FIXME: Alex broke this, setting back "true" ?
              //setState(ClientCCASessionState.IDLE, false);
              deliverCCAnswer((JCreditControlRequest) localEvent.getRequest(), (JCreditControlAnswer) localEvent.getAnswer());
              setState(ClientCCASessionState.IDLE, true);
              break;
            default:
              logger.warn("Wrong event type ({}) on state {}", eventType, state);
              break;
          }
          break;

        default:
          // any other state is bad
          setState(ClientCCASessionState.IDLE, true);
      }

      dispatch();
      return true;
    }
    catch (Exception e) {
      throw new InternalException(e);
    }
    finally {
      sendAndStateLock.unlock();
    }
  }

  @Override
  public Answer processRequest(Request request) {
    RequestDelivery rd = new RequestDelivery();
    rd.session = this;
    rd.request = request;
    super.scheduler.execute(rd);
    return null;
  }

  @Override
  public void receivedSuccessMessage(Request request, Answer answer) {
    AnswerDelivery ad = new AnswerDelivery();
    ad.session = this;
    ad.request = request;
    ad.answer = answer;
    super.scheduler.execute(ad);

  }

  @Override
  public void timeoutExpired(Request request) {
    if (request.getCommandCode() == JCreditControlAnswer.code) {
      try {
        handleSendFailure(null, null, request);
      }
      catch (Exception e) {
        logger.debug("Failure processing timeout message for request", e);
      }
    }
  }

  protected void startTx(JCreditControlRequest request) {
    long txTimerValue = context.getDefaultTxTimerValue();
    if (txTimerValue < 0) {
      txTimerValue = TX_TIMER_DEFAULT_VALUE;
    }
    stopTx();

    logger.debug("Scheduling TX Timer {}", txTimerValue);
    //this.txFuture = scheduler.schedule(new TxTimerTask(this, request), txTimerValue, TimeUnit.SECONDS);
    try {
      this.sessionData.setTxTimerRequest((Request) ((AppEventImpl) request).getMessage());
    }
    catch (Exception e) {
      throw new IllegalArgumentException("Failed to store request.", e);
    }
    this.sessionData.setTxTimerId(this.timerFacility.schedule(this.getSessionId(), TX_TIMER_NAME, TX_TIMER_DEFAULT_VALUE));
  }

  protected void stopTx() {
    Serializable txTimerId = this.sessionData.getTxTimerId();
    if (txTimerId != null) {
      this.sessionData.setTxTimerRequest(null);
      this.timerFacility.cancel(txTimerId);
      this.sessionData.setTxTimerId(null);
    }
  }

  /* (non-Javadoc)
   * @see org.jdiameter.common.impl.app.AppSessionImpl#onTimer(java.lang.String)
   */
  @Override
  public void onTimer(String timerName) {
    if (timerName.equals(TX_TIMER_NAME)) {
      new TxTimerTask(this, this.sessionData.getTxTimerRequest()).run();
    }
  }

  protected void setState(ClientCCASessionState newState) {
    setState(newState, true);
  }

  @SuppressWarnings("unchecked")
  protected void setState(ClientCCASessionState newState, boolean release) {
    try {
      IAppSessionState oldState = this.sessionData.getClientCCASessionState();
      this.sessionData.setClientCCASessionState(newState);

      for (StateChangeListener i : stateListeners) {
        i.stateChanged(this,(Enum) oldState, (Enum) newState);
      }

      if (newState == ClientCCASessionState.IDLE) {
        if (release) {
          this.release();
        }
        stopTx();
      }
    }
    catch (Exception e) {
      if (logger.isDebugEnabled()) {
        logger.debug("Failure switching to state " + this.sessionData.getClientCCASessionState() + " (release=" + release + ")", e);
      }
    }
  }

  @Override
  public void release() {
    if (isValid()) {
      try {
        this.sendAndStateLock.lock();
        super.release();
      }
      catch (Exception e) {
        logger.debug("Failed to release session", e);
      }
      finally {
        sendAndStateLock.unlock();
      }
    }
    else {
      logger.debug("Trying to release an already invalid session, with Session ID '{}'", getSessionId());
    }
  }

  protected void handleSendFailure(Exception e, Event.Type eventType, Message request) throws Exception {
    logger.debug("Failed to send message, type: {} message: {}, failure: {}", new Object[]{eventType, request, e != null ? e.getLocalizedMessage() : ""});
    try {
      this.sendAndStateLock.lock();
      ClientCCASessionState state = this.sessionData.getClientCCASessionState();
      // Event Based ----------------------------------------------------------
      if (isEventBased()) {
        int gatheredRequestedAction = this.sessionData.getGatheredRequestedAction();
        switch (state) {
          case PENDING_EVENT:
            if (gatheredRequestedAction == CHECK_BALANCE || gatheredRequestedAction == PRICE_ENQUIRY) {
              // Current State: PENDING_E
              // Event: Failure to send, temporary error, failed CC event answer received or Tx expired; requested action CHECK_BALANCE or PRICE_ENQUIRY
              // Action: Indicate service error
              // New State: IDLE
              setState(ClientCCASessionState.IDLE);
              context.indicateServiceError(this);
            }
            else if (gatheredRequestedAction == DIRECT_DEBITING) {
              switch (getLocalDDFH()) {
                case DDFH_TERMINATE_OR_BUFFER:
                  // Current State: PENDING_E
                  // Event: Failure to send; request action DIRECT_DEBITING; DDFH equal to TERMINATE_OR_BUFFER
                  // Action: Store request with T-flag
                  // New State: IDLE
                  request.setReTransmitted(true);
                  this.sessionData.setBuffer((Request) request);

                  setState(ClientCCASessionState.IDLE, false);
                  break;
                case DDFH_CONTINUE:
                  // Current State: PENDING_E
                  // Event: Failure to send, temporary error, failed CC event answer received or Tx expired; requested action DIRECT_DEBITING; DDFH equal to CONTINUE
                  // Action: Grant service to end user
                  // New State: IDLE
                  context.grantAccessOnDeliverFailure(this, request);
                  break;
                default:
                  logger.warn("Invalid Direct-Debiting-Failure-Handling AVP value {}", getLocalDDFH());
              }
            }
            else if (gatheredRequestedAction == REFUND_ACCOUNT) {
              // Current State: PENDING_E
              // Event: Failure to send or Tx expired; requested action REFUND_ACCOUNT
              // Action: Store request with T-flag
              // New State: IDLE
              setState(ClientCCASessionState.IDLE, false);
              request.setReTransmitted(true);
              this.sessionData.setBuffer((Request) request);
            }
            else {
              logger.warn("Invalid Requested-Action AVP value {}", gatheredRequestedAction);
            }
            break;
          case PENDING_BUFFERED:
            // Current State: PENDING_B
            // Event: Failure to send or temporary error
            // Action: -
            // New State: IDLE
            setState(ClientCCASessionState.IDLE, false);
            this.sessionData.setBuffer(null); // FIXME: Action does not mention, but ...
            break;
          default:
            logger.warn("Wrong event type ({}) on state {}", eventType, state);
            break;
        }
      }
      // Session Based --------------------------------------------------------
      else {
        switch (getLocalCCFH()) {
          case CCFH_CONTINUE:
            // Current State: PENDING_I
            // Event: Failure to send, or temporary error and CCFH equal to CONTINUE
            // Action: Grant service to end user
            // New State: IDLE

            // Current State: PENDING_U
            // Event: Failure to send, or temporary error and CCFH equal to CONTINUE
            // Action: Grant service to end user
            // New State: IDLE
            setState(ClientCCASessionState.IDLE, false);
            this.context.grantAccessOnDeliverFailure(this, request);
            break;

          default:
            // Current State: PENDING_I
            // Event: Failure to send, or temporary error and CCFH equal to TERMINATE or to RETRY_AND_TERMINATE
            // Action: Terminate end user�s service
            // New State: IDLE

            // Current State: PENDING_U
            // Event: Failure to send, or temporary error and CCFH equal to TERMINATE or to RETRY_AND_TERMINATE
            // Action: Terminate end user�s service
            // New State: IDLE
            this.context.denyAccessOnDeliverFailure(this, request);
            setState(ClientCCASessionState.IDLE, true);
            break;
        }
      }
      dispatch();
    }
    finally {
      this.sendAndStateLock.unlock();
    }
  }

  protected void handleFailureMessage(JCreditControlAnswer answer, JCreditControlRequest request, Event.Type eventType) {
    try {
      // Event Based ----------------------------------------------------------
      long resultCode = answer.getResultCodeAvp().getUnsigned32();
      ClientCCASessionState state = this.sessionData.getClientCCASessionState();
      Serializable txTimerId = this.sessionData.getTxTimerId();
      if (isEventBased()) {
        int gatheredRequestedAction = this.sessionData.getGatheredRequestedAction();
        switch (state) {
          case PENDING_EVENT:
            if (resultCode == END_USER_SERVICE_DENIED || resultCode == USER_UNKNOWN) {
              if (txTimerId != null) {
                // Current State: PENDING_E
                // Event: CC event answer received with result code END_USER_SERVICE_DENIED or USER_UNKNOWN and Tx running
                // Action: Terminate end user�s service
                // New State: IDLE
                context.denyAccessOnFailureMessage(this);
                deliverCCAnswer(request, answer);
                setState(ClientCCASessionState.IDLE);
              }
              else if (gatheredRequestedAction == DIRECT_DEBITING && txTimerId == null) {
                // Current State: PENDING_E
                // Event: Failed answer or answer received w/ result code END_USER_SERVICE DENIED or USER_UNKNOWN; requested action DIRECT_DEBITING; Tx expired
                // Action: -
                // New State: IDLE
                setState(ClientCCASessionState.IDLE);
              }
            }
            else if (resultCode == CREDIT_CONTROL_NOT_APPLICABLE && gatheredRequestedAction == DIRECT_DEBITING) {
              // Current State: PENDING_E
              // Event: CC event answer received with result code CREDIT_CONTROL_NOT_APPLICABLE; requested action DIRECT_DEBITING
              // Action: Grant service to end user
              // New State: IDLE
              context.grantAccessOnFailureMessage(this);
              deliverCCAnswer(request, answer);
              setState(ClientCCASessionState.IDLE);
            }
            else if (temporaryErrorCodes.contains(resultCode)) {
              if (gatheredRequestedAction == CHECK_BALANCE || gatheredRequestedAction == PRICE_ENQUIRY) {
                // Current State: PENDING_E
                // Event: Failure to send, temporary error, failed CC event answer received or Tx expired; requested action CHECK_BALANCE or PRICE_ENQUIRY
                // Action: Indicate service error
                // New State: IDLE
                context.indicateServiceError(this);
                deliverCCAnswer(request, answer);
                setState(ClientCCASessionState.IDLE);
              }
              else if (gatheredRequestedAction == DIRECT_DEBITING) {
                if (getLocalDDFH() == DDFH_CONTINUE) {
                  // Current State: PENDING_E
                  // Event: Failure to send, temporary error, failed CC event answer received or Tx expired; requested action DIRECT_DEBITING; DDFH equal to CONTINUE
                  // Action: Grant service to end user
                  // New State: IDLE
                  context.grantAccessOnFailureMessage(this);
                  deliverCCAnswer(request, answer);
                  setState(ClientCCASessionState.IDLE);
                }
                else if (getLocalDDFH() == DDFH_TERMINATE_OR_BUFFER && txTimerId != null) {
                  // Current State: PENDING_E
                  // Event: Failed CC event answer received or temporary error; requested action DIRECT_DEBITING; DDFH equal to TERMINATE_OR_BUFFER and Tx running
                  // Action: Terminate end user�s service
                  // New State: IDLE
                  context.denyAccessOnFailureMessage(this);
                  deliverCCAnswer(request, answer);
                  setState(ClientCCASessionState.IDLE);
                }
              }
              else if (gatheredRequestedAction == REFUND_ACCOUNT) {
                // Current State: PENDING_E
                // Event: Temporary error, and requested action REFUND_ACCOUNT
                // Action: Store request
                // New State: IDLE
                this.sessionData.setBuffer((Request) request.getMessage());
                setState(ClientCCASessionState.IDLE, false);
              }
              else {
                logger.warn("Invalid combination for CCA Client FSM: State {}, Result-Code {}, Requested-Action {}, DDFH {}, Tx {}",
                    new Object[]{state, resultCode, gatheredRequestedAction, getLocalDDFH(), txTimerId});
              }
            }
            else { // Failure
              if (gatheredRequestedAction == CHECK_BALANCE || gatheredRequestedAction == PRICE_ENQUIRY) {
                // Current State: PENDING_E
                // Event: Failure to send, temporary error, failed CC event answer received or Tx expired; requested action CHECK_BALANCE or PRICE_ENQUIRY
                // Action: Indicate service error
                // New State: IDLE
                context.indicateServiceError(this);
                deliverCCAnswer(request, answer);
                setState(ClientCCASessionState.IDLE);
              }
              else if (gatheredRequestedAction == DIRECT_DEBITING) {
                if (getLocalDDFH() == DDFH_CONTINUE) {
                  // Current State: PENDING_E
                  // Event: Failure to send, temporary error, failed CC event answer received or Tx expired; requested action DIRECT_DEBITING; DDFH equal to CONTINUE
                  // Action: Grant service to end user
                  // New State: IDLE
                  context.grantAccessOnFailureMessage(this);
                  deliverCCAnswer(request, answer);
                  setState(ClientCCASessionState.IDLE);
                }
                else if (getLocalDDFH() == DDFH_TERMINATE_OR_BUFFER && txTimerId != null) {
                  // Current State: PENDING_E
                  // Event: Failed CC event answer received or temporary error; requested action DIRECT_DEBITING; DDFH equal to TERMINATE_OR_BUFFER and Tx running
                  // Action: Terminate end user�s service
                  // New State: IDLE
                  context.denyAccessOnFailureMessage(this);
                  deliverCCAnswer(request, answer);
                  setState(ClientCCASessionState.IDLE);
                }
              }
              else if (gatheredRequestedAction == REFUND_ACCOUNT) {
                // Current State: PENDING_E
                // Event: Failed CC event answer received; requested action REFUND_ACCOUNT
                // Action: Indicate service error and delete request
                // New State: IDLE
                this.sessionData.setBuffer(null);
                context.indicateServiceError(this);
                deliverCCAnswer(request, answer);
                setState(ClientCCASessionState.IDLE);
              }
              else {
                logger.warn("Invalid combination for CCA Client FSM: State {}, Result-Code {}, Requested-Action {}, DDFH {}, Tx {}",
                    new Object[]{state, resultCode, gatheredRequestedAction, getLocalDDFH(), txTimerId});
              }
            }
            break;

          case PENDING_BUFFERED:
            // Current State: PENDING_B
            // Event: Failed CC answer received
            // Action: Delete request
            // New State: IDLE
            this.sessionData.setBuffer(null);
            setState(ClientCCASessionState.IDLE, false);
            break;
          default:
            logger.warn("Wrong event type ({}) on state {}", eventType, state);
        }
      }
      // Session Based --------------------------------------------------------
      else {
        switch (state) {
          case PENDING_INITIAL:
            if (resultCode == CREDIT_CONTROL_NOT_APPLICABLE) {
              // Current State: PENDING_I
              // Event: CC initial answer received with result code equal to CREDIT_CONTROL_NOT_APPLICABLE
              // Action: Grant service to end user
              // New State: IDLE
              context.grantAccessOnFailureMessage(this);
              setState(ClientCCASessionState.IDLE, false);
            }
            else if ((resultCode == END_USER_SERVICE_DENIED) || (resultCode == USER_UNKNOWN)) {
              // Current State: PENDING_I
              // Event: CC initial answer received with result code END_USER_SERVICE_DENIED or USER_UNKNOWN
              // Action: Terminate end user�s service
              // New State: IDLE
              context.denyAccessOnFailureMessage(this);
              setState(ClientCCASessionState.IDLE, false);
            }
            else {
              // Temporary errors and others
              switch (getLocalCCFH()) {
                case CCFH_CONTINUE:
                  // Current State: PENDING_I
                  // Event: Failed CC initial answer received and CCFH equal to CONTINUE
                  // Action: Grant service to end user
                  // New State: IDLE
                  context.grantAccessOnFailureMessage(this);
                  setState(ClientCCASessionState.IDLE, false);
                  break;
                case CCFH_TERMINATE:
                case CCFH_RETRY_AND_TERMINATE:
                  // Current State: PENDING_I
                  // Event: Failed CC initial answer received and CCFH equal to TERMINATE or to RETRY_AND_TERMINATE
                  // Action: Terminate end user�s service
                  // New State: IDLE
                  context.denyAccessOnFailureMessage(this);
                  setState(ClientCCASessionState.IDLE, false);
                  break;
                default:
                  logger.warn("Invalid value for CCFH: {}", getLocalCCFH());
                  break;
              }
            }
            break;

          case PENDING_UPDATE:
            if (resultCode == CREDIT_CONTROL_NOT_APPLICABLE) {
              // Current State: PENDING_U
              // Event: CC update answer received with result code equal to CREDIT_CONTROL_NOT_APPLICABLE
              // Action: Grant service to end user
              // New State: IDLE
              context.grantAccessOnFailureMessage(this);
              setState(ClientCCASessionState.IDLE, false);
            }
            else if (resultCode == END_USER_SERVICE_DENIED) {
              // Current State: PENDING_U
              // Event: CC update answer received with result code END_USER_SERVICE_DENIED
              // Action: Terminate end user�s service
              // New State: IDLE
              context.denyAccessOnFailureMessage(this);
              setState(ClientCCASessionState.IDLE, false);
            }
            else {
              // Temporary errors and others
              switch (getLocalCCFH()) {
                case CCFH_CONTINUE:
                  // Current State: PENDING_U
                  // Event: Failed CC update answer received and CCFH equal to CONTINUE
                  // Action: Grant service to end user
                  // New State: IDLE
                  context.grantAccessOnFailureMessage(this);
                  setState(ClientCCASessionState.IDLE, false);
                  break;
                case CCFH_TERMINATE:
                case CCFH_RETRY_AND_TERMINATE:
                  // Current State: PENDING_U
                  // Event: Failed CC update answer received and CCFH equal to CONTINUE or to RETRY_AND_CONTINUE
                  // Action: Terminate end user�s service
                  // New State: IDLE
                  context.denyAccessOnFailureMessage(this);
                  setState(ClientCCASessionState.IDLE, false);
                  break;
                default:
                  logger.warn("Invalid value for CCFH: " + getLocalCCFH());
                  break;
              }
            }
            break;

          default:
            logger.warn("Wrong event type ({}) on state {}", eventType, state);
        }
      }
    }
    catch (Exception e) {
      if (logger.isDebugEnabled()) {
        logger.debug("Failure handling failure message for Event " + answer + " (" + eventType + ") and Request " + request, e);
      }
    }
  }

  protected void handleTxExpires(Message message) {
    // Event Based ----------------------------------------------------------
    ClientCCASessionState state = this.sessionData.getClientCCASessionState();
    Serializable txTimerId = this.sessionData.getTxTimerId();
    if (isEventBased()) {
      int gatheredRequestedAction = this.sessionData.getGatheredRequestedAction();
      if (gatheredRequestedAction == CHECK_BALANCE || gatheredRequestedAction == PRICE_ENQUIRY) {
        // Current State: PENDING_E
        // Event: Failure to send, temporary error, failed CC event answer received or Tx expired; requested action CHECK_BALANCE or PRICE_ENQUIRY
        // Action: Indicate service error
        // New State: IDLE
        context.indicateServiceError(this);
        setState(ClientCCASessionState.IDLE);
      }
      else if (gatheredRequestedAction == DIRECT_DEBITING) {
        int gatheredDDFH = this.sessionData.getGatheredDDFH();
        if (gatheredDDFH == DDFH_TERMINATE_OR_BUFFER) {
          // Current State: PENDING_E
          // Event: Temporary error; requested action DIRECT_DEBITING; DDFH equal to TERMINATE_OR_BUFFER; Tx expired
          // Action: Store request
          // New State: IDLE

          this.sessionData.setBuffer((Request) message);
          setState(ClientCCASessionState.IDLE, false);
        }
        else {
          // Current State: PENDING_E
          // Event: Tx expired; requested action DIRECT_DEBITING
          // Action: Grant service to end user
          // New State: PENDING_E
          context.grantAccessOnTxExpire(this);
          setState(ClientCCASessionState.PENDING_EVENT);
        }
      }
      else if (gatheredRequestedAction == REFUND_ACCOUNT) {
        // Current State: PENDING_E
        // Event: Failure to send or Tx expired; requested action REFUND_ACCOUNT
        // Action: Store request with T-flag
        // New State: IDLE
        message.setReTransmitted(true);
        this.sessionData.setBuffer((Request) message);
        setState(ClientCCASessionState.IDLE, false);
      }
    }
    // Session Based --------------------------------------------------------
    else {
      switch (state) {
        case PENDING_INITIAL:
          switch (getLocalCCFH()) {
            case CCFH_CONTINUE:
            case CCFH_RETRY_AND_TERMINATE:
              // Current State: PENDING_I
              // Event: Tx expired and CCFH equal to CONTINUE or to RETRY_AND_TERMINATE
              // Action: Grant service to end user
              // New State: PENDING_I
              context.grantAccessOnTxExpire(this);
              break;

            case CCFH_TERMINATE:
              // Current State: PENDING_I
              // Event: Tx expired and CCFH equal to TERMINATE
              // Action: Terminate end user�s service
              // New State: IDLE
              context.denyAccessOnTxExpire(this);
              setState(ClientCCASessionState.IDLE, true);
              break;

            default:
              logger.warn("Invalid value for CCFH: " + getLocalCCFH());
              break;
          }
          break;

        case PENDING_UPDATE:
          switch (getLocalCCFH()) {
            case CCFH_CONTINUE:
            case CCFH_RETRY_AND_TERMINATE:
              // Current State: PENDING_U
              // Event: Tx expired and CCFH equal to CONTINUE or to RETRY_AND_TERMINATE
              // Action: Grant service to end user
              // New State: PENDING_U
              context.grantAccessOnTxExpire(this);
              break;

            case CCFH_TERMINATE:
              // Current State: PENDING_U
              // Event: Tx expired and CCFH equal to TERMINATE
              // Action: Terminate end user�s service
              // New State: IDLE
              context.denyAccessOnTxExpire(this);
              setState(ClientCCASessionState.IDLE, true);
              break;

            default:
              logger.error("Bad value of CCFH: " + getLocalCCFH());
              break;
          }
          break;

        default:
          logger.error("Unknown state (" + state + ") on txExpire");
          break;
      }
    }
  }

  /**
   * This makes checks on queue, moves it to proper state if event there is
   * present on Open state ;]
   */
  protected void dispatch() {
    // Event Based ----------------------------------------------------------
    if (isEventBased()) {
      // Current State: IDLE
      // Event: Request in storage
      // Action: Send stored request
      // New State: PENDING_B
      Request buffer = this.sessionData.getBuffer();
      if (buffer != null) {
        setState(ClientCCASessionState.PENDING_BUFFERED);
        try {
          dispatchEvent(new AppRequestEventImpl(buffer));
        }
        catch (Exception e) {
          try {
            handleSendFailure(e, Event.Type.SEND_EVENT_REQUEST, buffer);
          }
          catch (Exception e1) {
            logger.error("Failure handling buffer send failure", e1);
          }
        }
      }
    }
    // Session Based --------------------------------------------------------
    else {
      if (this.sessionData.getClientCCASessionState() == ClientCCASessionState.OPEN && eventQueue.size() > 0) {
        try {
          this.handleEvent(eventQueue.remove(0));
        }
        catch (Exception e) {
          logger.error("Failure handling queued event", e);
        }
      }
    }
  }

  protected void deliverCCAnswer(JCreditControlRequest request, JCreditControlAnswer answer) {
    try {
      listener.doCreditControlAnswer(this, request, answer);
    }
    catch (Exception e) {
      logger.warn("Failure delivering CCA Answer", e);
    }
  }

  protected void extractFHAVPs(JCreditControlRequest request, JCreditControlAnswer answer) {
    if (answer != null) {
      try {
        if (answer.isCreditControlFailureHandlingAVPPresent()) {
          this.sessionData.setGatheredCCFH(answer.getCredidControlFailureHandlingAVPValue());
        }
      }
      catch (Exception e) {
        logger.debug("Failure trying to obtain Credit-Control-Failure-Handling AVP value", e);
      }
      try {
        if (answer.isDirectDebitingFailureHandlingAVPPresent()) {
          this.sessionData.setGatheredDDFH(answer.getDirectDebitingFailureHandlingAVPValue());
        }
      }
      catch (Exception e) {
        logger.debug("Failure trying to obtain Direct-Debit-Failure-Handling AVP value", e);
      }
      if (!this.sessionData.isRequestTypeSet()) {
        this.sessionData.setRequestTypeSet(true);
        // No need to check if it exists.. it must, if not fail with exception
        this.sessionData.setEventBased(answer.getRequestTypeAVPValue() == EVENT_REQUEST);
      }
    }
    else if (request != null) {
      try {
        if (request.isRequestedActionAVPPresent()) {
          this.sessionData.setGatheredRequestedAction(request.getRequestedActionAVPValue());
        }
      }
      catch (Exception e) {
        logger.debug("Failure trying to obtain Request-Action AVP value", e);
      }

      if (!this.sessionData.isRequestTypeSet()) {
        this.sessionData.setRequestTypeSet(true);
        // No need to check if it exists.. it must, if not fail with exception
        this.sessionData.setEventBased(request.getRequestTypeAVPValue() == EVENT_REQUEST);
      }
    }
  }

  protected void deliverRAR(ReAuthRequest request) {
    try {
      listener.doReAuthRequest(this, request);
    }
    catch (Exception e) {
      logger.debug("Failure delivering RAR", e);
    }
  }

  protected void dispatchEvent(AppEvent event) throws InternalException, IllegalDiameterStateException, RouteException, OverloadException {
    session.send(event.getMessage(), this);
  }

  protected boolean isProvisional(long resultCode) {
    return resultCode >= 1000 && resultCode < 2000;
  }

  protected boolean isSuccess(long resultCode) {
    return resultCode >= 2000 && resultCode < 3000;
  }

  protected boolean isFailure(long code) {
    return (!isProvisional(code) && !isSuccess(code) && ((code >= 3000 && code < 6000)) && !temporaryErrorCodes.contains(code));
  }

  /* (non-Javadoc)
   * @see org.jdiameter.common.impl.app.AppSessionImpl#isReplicable()
   */
  @Override
  public boolean isReplicable() {
    return true;
  }

  private class TxTimerTask implements Runnable {
    private ClientCCASession session = null;
    private Request request = null;

    private TxTimerTask(ClientCCASession session, Request request) {
      super();
      this.session = session;
      this.request = request;
    }

    @Override
    public void run() {
      try {
        sendAndStateLock.lock();
        logger.debug("Fired TX Timer");
        sessionData.setTxTimerId(null);
        try {
          context.txTimerExpired(session);
        }
        catch (Exception e) {
          logger.debug("Failure handling TX Timer Expired", e);
        }
        JCreditControlRequest req = factory.createCreditControlRequest(request);
        handleEvent(new Event(Event.Type.Tx_TIMER_FIRED, req, null));
      }
      catch (InternalException e) {
        logger.error("Internal Exception", e);
      }
      catch (OverloadException e) {
        logger.error("Overload Exception", e);
      }
      catch (Exception e) {
        logger.error("Exception", e);
      }
      finally {
        sendAndStateLock.unlock();
      }
    }
  }

  private class RequestDelivery implements Runnable {
    ClientCCASession session;
    Request request;

    @Override
    public void run() {
      try {
        switch (request.getCommandCode()) {
          case ReAuthAnswer.code:
            handleEvent(new Event(Event.Type.RECEIVED_RAR, factory.createReAuthRequest(request), null));
            break;

          default:
            listener.doOtherEvent(session, new AppRequestEventImpl(request), null);
            break;
        }
      }
      catch (Exception e) {
        logger.debug("Failure processing request", e);
      }
    }
  }

  private class AnswerDelivery implements Runnable {
    ClientCCASession session;
    Answer answer;
    Request request;

    @Override
    public void run() {
      try {
        switch (request.getCommandCode()) {
          case JCreditControlAnswer.code:
            JCreditControlRequest _request = factory.createCreditControlRequest(request);
            JCreditControlAnswer _answer = factory.createCreditControlAnswer(answer);
            extractFHAVPs(null, _answer );
            handleEvent(new Event(false, _request, _answer));
            break;

          default:
            listener.doOtherEvent(session, new AppRequestEventImpl(request), new AppAnswerEventImpl(answer));
            break;
        }
      }
      catch (Exception e) {
        logger.debug("Failure processing success message", e);
      }
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((sessionData == null) ? 0 : sessionData.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    ClientCCASessionImpl other = (ClientCCASessionImpl) obj;
    if (sessionData == null) {
      if (other.sessionData != null) {
        return false;
      }
    }
    else if (!sessionData.equals(other.sessionData)) {
      return false;
    }
    return true;
  }

}
