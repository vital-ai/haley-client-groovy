package ai.haley.api

import ai.haley.api.HaleyAPI.CachedCredentials
import ai.haley.api.HaleyAPI.MessageHandler
import ai.haley.api.impl.HaleyFileUploadImplementation
import ai.haley.api.session.HaleySession
import ai.haley.api.session.HaleyStatus
import ai.vital.domain.FileNode
import ai.vital.domain.Login
import ai.vital.domain.UserSession
import ai.vital.service.vertx3.binary.ResponseMessage
import ai.vital.service.vertx3.websocket.VitalServiceAsyncWebsocketClient
import ai.vital.vitalservice.VitalStatus
import ai.vital.vitalservice.query.ResultList
import ai.vital.vitalsigns.json.JSONSerializer
import ai.vital.vitalsigns.model.DomainModel
import ai.vital.vitalsigns.model.GraphObject
import ai.vital.vitalsigns.model.VitalApp
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.hp.hpl.jena.rdf.arp.JenaHandler
import com.vitalai.aimp.domain.AIMPMessage
import com.vitalai.aimp.domain.Channel as AIMPChannel
import com.vitalai.aimp.domain.FileQuestion
import com.vitalai.aimp.domain.HeartbeatMessage
import com.vitalai.aimp.domain.ListChannelsRequestMessage
import com.vitalai.aimp.domain.QuestionMessage
import com.vitalai.aimp.domain.UserLeftApp
import com.vitalai.aimp.domain.UserLoggedIn
import com.vitalai.aimp.domain.UserLoggedOut
import java.nio.channels.Channel
import java.util.Map.Entry
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpClientResponse
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CountDownLatch


/*
 * Haley API
 * 
 * 
 * Includes synchronous and asynchronous calls
 * 
 * Multiple instances of HaleyAPI may be instantiated.  However, there is an underlying
 * singleton for vitalsigns, so domain classes will be shared across all instances.
 * 
 * This version may consider a single endpoint only.
 * 
 * There may be a conflict when opening multiple endpoints with conflicting domains.
 * This can throw an exception and not open the session.
 * 
 */

class HaleyAPI {
		
	private static class MessageHandler {
		
		Closure callback
		
		List<Class<? extends AIMPMessage>> primaryClasses = []
		
		List<Class<? extends AIMPMessage>> classes = []
		
	}
	
	private static class CachedCredentials {
		
		String username
		
		String password
		
	}
	
	private final static Logger log = LoggerFactory.getLogger(HaleyAPI.class)
	
	public final static String VERTX_STREAM_SUBSCRIBE = 'vertx-stream-subscribe';
	
	public final static String VERTX_STREAM_UNSUBSCRIBE = 'vertx-stream-unsubscribe';

	// public final static String GROOVY_REGISTER_STREAM_HANDLER = 'groovy-register-stream-handler';
	
	// public final static String GROOVY_UNREGISTER_STREAM_HANDLER = 'groovy-unregister-stream-handler';
	
	// public final static String GROOVY_LIST_STREAM_HANDLERS = 'groovy-list-stream-handlers';
	
	// private List<HaleySession> sessions = []
	
	
	private HaleySession haleySessionSingleton

	private VitalServiceAsyncWebsocketClient vitalService
	
	private String streamName = 'haley'
		
	private List<MessageHandler> handlers = []

	// requestURI -> callback
	private Map<String, Closure> requestHandlers = [:]
	
	Closure defaultHandler = null;
	
	Closure handlerFunction = null;
		
	Closure closeFrameHandler = null
	
	
	private syncdomains = false
	
	private Map<String, CachedCredentials> cachedCredentials = [:]
	
	private List<Closure> reconnectListeners = []
	
	private Long lastActivityTimestamp = null; 
	
	public boolean addReconnectListener(Closure reconnectListener) {
		if(reconnectListeners.contains(reconnectListener)) {
			return false
		}
		reconnectListeners.add(reconnectListener)
		return true
	}
	
	public boolean removeReconnectListener(Closure reconnectListener) {
		if(!reconnectListeners.contains(reconnectListener)) {
			return false
		}
		reconnectListeners.remove(reconnectListener)
		
		return true
	}
	
	public void clearReconnectListeners() {
		
		reconnectListeners = []
	}
	
	public void closeWebsocket() {
		
		if(vitalService.webSocket != null) {
			
			try {
				
				vitalService.closed = true
				
				vitalService.webSocket.close()
			
			} catch(Exception e) {
			
				log.error("Exception closing websocket: " + e.localizedMessage) 
			}
			
			vitalService.webSocket = null
		}		
	}
	
	private String _checkSession(HaleySession haleySession) {
		
		if(this.haleySessionSingleton == null) return 'no active haley session found';
		
		if(this.haleySessionSingleton != haleySession) return 'unknown haley session';
		
		return null;
		
	}
	
	// nodejs style callback
	
	private void _sendLoggedInMsg(Closure callback) {

		UserLoggedIn userLoggedIn = new UserLoggedIn()
		
		this.sendMessage(this.haleySessionSingleton, userLoggedIn, []) { HaleyStatus status ->
			
			if(status.ok) {
				callback(null)
			} else {
			
				log.error("Error when sending loggedin message: ", status.errorMessage);
			
				callback("ERROR: " + status.errorMessage)
			}
			
		}

	//		var msg = vitaljs.graphObject({type: 'http://vital.ai/ontology/vital-aimp#UserLoggedIn'});
	//		
	//		this.sendMessage(this.haleySessionSingleton, msg, [], function(error){
	//			
	//			if(error) {
	//				console.error("Error when sending loggedin message: ", error);
	//				callback(error);
	//			} else {
	//				callback(null);
	//			}
	//			
	//		})
			
	}
	
	// init a new instance
	// default is to use only locally available domains
	
	// use for mock case
	public HaleyAPI() {
	
	}		
		
	public HaleyAPI(VitalServiceAsyncWebsocketClient websocketClient) {
		
		this.vitalService = websocketClient
		
		this.vitalService.reconnectHandler = { Void v -> 
			
			log.info("Notifying reconnect listeners: [${this.reconnectListeners.size()}]")
						
			for(Closure rl : this.reconnectListeners) {
							
				rl.call(this.haleySessionSingleton)
							
			}

		}
		
		
		this.syncdomains = false
		
	}
	
	
	
	
	// open session to haley service
	
	public HaleySession openSession() {
	
		throw new Exception("Blocking version not implemented yet")
		
		

	}
	
	// open session to local haley server, given endpoint
	
	public HaleySession openSession(String endpoint) {
		
		throw new Exception("Blocking version not implemented yet.")
		
		
		
	}
	
	private void _streamHandler(ResultList msgRL) {

		AIMPMessage m = msgRL.first();
	
		log.info("Stream " + this.streamName + " received message: {} payload size {}", m.getClass().getCanonicalName(), msgRL.results.size() - 1);
		
		int c = 0;
	
		Class<? extends AIMPMessage> type = m.getClass();
	
		// requestURI handler
		String requestURI = m.requestURI
	
		if(requestURI != null) {
		
			Closure h = this.requestHandlers[requestURI];
		
			if(h != null) {
			
				log.info("Notifying requestURI handler", requestURI);
			
				Object cbRes = h(msgRL);
			
				if(cbRes != null && cbRes == false) {
				
					log.info("RequestURI handler returned false, unregistering.");
				
					this.requestHandlers.remove(requestURI);
				
				} else {
				
					log.info("RequestURI handler returned non-false, still registered.");
				
				}
			
				return;
			
			}
		
		}
		
		// primary classes
		for(MessageHandler h : this.handlers) {
			
			for(Class<? extends AIMPMessage> pc : h.primaryClasses) {
				
				if(pc == type) {
				
					log.info("Notifying primary type handler: ", h.primaryClasses);
					
					h.callback(msgRL)
					c++;
					return
						
				}
				
			}
			
		}
		
	
		for(MessageHandler h : this.handlers) {
		
			for(Class<? extends AIMPMessage> pc : h.classes) {
				
				if(pc.isAssignableFrom(type)) {
					
					log.info("Notifying secondary type handler: ", h.classes);
					
					h.callback(msgRL);
					c++;
					return;
					
				}
				
			}
			
		}
		
	
		if(this.defaultHandler != null) {
			
			log.info("Notifying default handler.");
			
			this.defaultHandler(msgRL);
			
			c++
			
			return;
		}
		
		
		log.info("Notified " + c + " msg handlers.");

	}
	
	volatile boolean canceled = false
	
	public void openSession(Closure callback) {
			
		ExecutorService executorService = Executors.newSingleThreadExecutor()
		
		Future<?> callFunctionFuture
	
		CountDownLatch latch = new CountDownLatch(1)
			
		if(this.haleySessionSingleton != null) {
			callback('active session already detected', null)
			return
		}

		this.handlerFunction = { ResultList rl ->
			
			log.info("Message received: " + rl)
			
			_streamHandler(rl)
		}
		
		Closure closeFrameHandler = {  ->
			
			log.info("HaleyAPI Close Frame Received")
			
			canceled = true
			
			if (callFunctionFuture != null) {
				
				// this can trigger an exception
				// to get past the await
				// but counting it down should also clear it
				callFunctionFuture.cancel(true)
			}	
			
			latch.countDown()
		}
		
		this.closeFrameHandler = closeFrameHandler
		
		vitalService.closeFrameHandler = this.closeFrameHandler
		
		log.info('subscribing to stream ', this.streamName);
		
		// get out of vertx thread
		
		callFunctionFuture = executorService.submit({
			
			try {	
		
				// launch separate thread to await	
				ExecutorService innerExecutorService = Executors.newSingleThreadExecutor()
				
				innerExecutorService.submit({
				
					vitalService.callFunction(VitalServiceAsyncWebsocketClient.GROOVY_REGISTER_STREAM_HANDLER, [streamName: this.streamName, handlerFunction: this.handlerFunction] ) { ResponseMessage regRes ->
			
						if(regRes.exceptionType) {
							
							latch.countDown()
							
							if(canceled == false) {
							
								callback(regRes.exceptionType + ' - ' + regRes.exceptionMessage, null)
							
							}
							return
						}
			
						ResultList regRL = regRes.response
			
						if(regRL.status.status != VitalStatus.Status.ok) {
							
							latch.countDown()
							
							if(canceled == false) {
								
								callback("ERROR: " + regRL.status.message, null)
							
							}
							
							return
						}
			
						log.info('registered handler to ' + this.streamName)
			
						// Note: don't call callback if already canceled
						
						Closure subscribeHandler = { ResponseMessage subRes ->
				
							if(subRes.exceptionType) {
							
								latch.countDown()
								
								log.error("ERROR: " + subRes.exceptionType + ' - ' + subRes.exceptionMessage)
								
								if(canceled == false) {
										
									callback(subRes.exceptionType + ' - ' + subRes.exceptionMessage, null)
								}
							
								
								return
							}
				
							ResultList subRL = regRes.response
				
							if(subRL.status.status != VitalStatus.Status.ok) {
							
								latch.countDown()
								
								log.error( "ERROR: " + subRL.status.message )
								
								
								if(canceled == false) {
								
									callback("ERROR: " + subRL.status.message, null)
								}
							
								
								
								return
							}
				
							latch.countDown()
							
							if(canceled == false) {
						
								log.info('subscribed to ' + this.streamName)
								
								this.haleySessionSingleton = new HaleySession(sessionID: vitalService.sessionID)
				
								callback(null, this.haleySessionSingleton)
							}
						
						}
			
						vitalService.callFunction(VitalServiceAsyncWebsocketClient.VERTX_STREAM_SUBSCRIBE, [ streamName: this.streamName ], subscribeHandler )			
					}
				})
					
			} catch (Exception e) {
				
                log.error("Error in callFunction Register/Subscribe", e)
          }
		  
		  try {
			  
			  latch.await()
			  
			  log.info("After await.  Canceled: " + canceled)
			  
			  
		  } catch (InterruptedException e) {
			  
			  log.info("Register/Subscribe method was interrupted")
		  }
		  
		  // pass canceled error back to callback
		  
		  if(canceled == true) {
			  
			  log.info("Register/Subscribe method was canceled")
			  
			  callback("Register/Subscribe method was canceled", null)
		  }
		})
		
	}
	
	public HaleyStatus closeSession(HaleySession session) {
		
		throw new Exception("Blocking version not implemented yet.")
		
		//		HaleyStatus status = new HaleyStatus()
		//		
		//		
		//		sessions.remove(session)
		//		
		//		return status
		
	}
	
	public void closeSession(HaleySession session, Closure callback) {
	
		throw new Exception("not implemented yet.")
		
		//		HaleyStatus status = new HaleyStatus()
		//		
		//		sessions.remove(session)
		//		
		//		callback.call(status)
		
	}
	
	
	public HaleyStatus closeAllSessions() {
		
		throw new Exception("Blocking version not implemented yet.")
		
		//		HaleyStatus status = new HaleyStatus()
		//		
		//		int closed = 0
		//		
		//		// do close
		//		sessions.each { session -> 
		//			
		//			closed++
		//			
		//		}
		//
		//		sessions.clear()
		//				
		//		
		//		return status
		
	}
	
	public void closeAllSessions(Closure callback) {
	
		throw new Exception("not implemented yet.")
		
		//		HaleyStatus status = new HaleyStatus()
		//		
		//		int closed = 0
		//		
		//		// do close
		//		sessions.each { session -> 
		//			closed++ 
		//		}
		//		
		//		sessions.clear()
		//		
		//		callback.call(status)
		
	
	}
	
	public Collection<HaleySession> getSessions() {
		
		if(this.haleySessionSingleton != null) {
			return [ this.haleySessionSingleton ]
		} else {
			return []
		}
		
		//		return sessions.asImmutable()
		
	}
	
	
	public HaleyStatus authenticateSession(HaleySession session, String username, String password) {

		throw new Exception("Blocking version not implemented yet.") 
				
	// HaleyStatus status = new HaleyStatus()
	// return status
		
	}
	
	public void authenticateSession(HaleySession session, String username, String password, Closure callback) {
		authenticateSessionImpl(session, username, password, true, callback)
	}
	
	private void authenticateSessionImpl(HaleySession session, String username, String password, boolean sendLoggedInMsg, Closure callback) {
		
		String error = this._checkSession(session);
		
		if(error) {
			callback(HaleyStatus.error(error));
			return;
		}
		
		if(session.isAuthenticated()) {
			callback(HaleyStatus.error('session already authenticated.'));
			return;
		}
		
		this.vitalService.callFunction('vitalauth.login', [loginType: 'Login', username: username, password: password], { ResponseMessage loginRes ->
				
			if(loginRes.exceptionType) {
				callback(HaleyStatus.error("Logging in exception: ${loginRes.exceptionType} - ${loginRes.exceptionMessage}"))
				return
			}
			
			ResultList res = loginRes.response
			
			if(res.status.status != VitalStatus.Status.ok) {
				callback(HaleyStatus.error("Logging in failed: ${res.status.message}"))
				return
			}
						
			log.info("auth success.");
	
			// logged in successfully keep session for further requests
			
			UserSession userSession = res.iterator(UserSession.class).next();
			
			if(session == null) {
				callback(HaleyStatus.error("No session object in positive login response."))
				return
			}
			
			Login userLogin = res.iterator(Login.class).next();
			
			if(userLogin == null) {
				callback(HaleyStatus.error("No login object in positive login response."))
				return
			}
			
			log.info("Session obtained: ${userSession.sessionID}")
			
					
			cachedCredentials.put(session.sessionID, new CachedCredentials(username: username, password: password))
			
			//set it in the client for future requests
			
			session.authSessionID = userSession.sessionID
			session.authAccount = userLogin
			session.authenticated = true
			
			//appSessionID must be set in order to send auth messages
			vitalService.appSessionID = userSession.sessionID
			
			
			callback(HaleyStatus.ok())
				
			/*		
			
			if(sendLoggedInMsg) {
				
				_sendLoggedInMsg() { String sendError ->
				
					if(sendError) {
						callback(HaleyStatus.error(sendError))
					} else {
						callback(HaleyStatus.ok())
						
					}
				
				}
				
			} else {
			
				callback(HaleyStatus.ok())
				
			}
			
			*/
			
			
			
		})

	}
	
	public HaleyStatus unauthenticateSession(HaleySession session) {
		
		throw new Exception("blocking version not implemented yet.")
		
		//		HaleyStatus status = new HaleyStatus()
		
		//		return status
		
	}
	
	public void unauthenticateSession(HaleySession session, Closure callback) {
	
		throw new Exception("not implemented yet.")
		
		//		HaleyStatus status = new HaleyStatus()

		//		callback.call(status)
		
	}
	
	public void sendMessageWithRequestCallback(HaleySession haleySession, AIMPMessage aimpMessage, List<GraphObject> payload, Closure requestCallback, Closure sendOpCallback) {
		
		HaleyStatus status = this.registerRequestCallback(aimpMessage, requestCallback)
		
		if(!status.ok) {
			sendOpCallback(status)
			return
		}
		
		this.sendMessage(haleySession, aimpMessage, payload, sendOpCallback)
			
	}
	
	
	// haley status callback
	public void sendMessage(HaleySession haleySession, AIMPMessage aimpMessage, List<GraphObject> payload, Closure callback) {
		sendMessageImpl(haleySession, aimpMessage, payload, 0, callback)
	}
		
	// internal call
	private void sendMessageImpl(HaleySession haleySession, AIMPMessage aimpMessage, List<GraphObject> payload, int retry, Closure callback) {
			
		String error = this._checkSession(haleySession)
		
		if(error) {
			callback(HaleyStatus.error(error))
			return;
		}
		
		if(aimpMessage == null) {
			callback(HaleyStatus.error("aimpMessage must not be null."));
			return;
		}
		
		for(p in payload){
			if(p == null) {				
				callback(HaleyStatus.error("payload object must not be null."));
				return;
			}
		}
		
		for(p in payload){
			if( (!p instanceof GraphObject)) {
				callback(HaleyStatus.error("payload object must be a GraphObject."));
				return;
			}
		}
			
		if(aimpMessage.URI == null) {
			aimpMessage.generateURI((VitalApp) null)
		}
		
		String sessionID = haleySession.getSessionID()
	
		Login authAccount = haleySession.getAuthAccount()
		
		String masterUserID = aimpMessage.masterUserID
		
		if(masterUserID) {
			
			if(authAccount == null) {
				callback(HaleyStatus.error("No auth account available - cannot use masterUserID."));
				return
			}
			
			String currentUserID = authAccount.username
			if(currentUserID != masterUserID) {
				callback(HaleyStatus.error("Master and current userID are different: " + masterUserID + " vs " + currentUserID))
				return
			}
			
			String effectiveUserID = aimpMessage.userID
			if(effectiveUserID == null) {
				callback(HaleyStatus.error("No userID in the message, it is required when using masterUserID tunneling."))
				return
			}
			
			String endpointURI = aimpMessage.endpointURI
			if(!endpointURI) {
				callback(HaleyStatus.error("masterUserID may only be used with endpointURI."))
				return
			}
			
			if(masterUserID == effectiveUserID) {
				callback(HaleyStatus.error("masterUserID should not be equal to effective userID: " + masterUserID + " vs " + currentUserID))
				return
			}
			
		} else {
		
			if( authAccount != null ) {
			
				String userID = aimpMessage.userID
			
				if(userID == null) {
					aimpMessage.userID = authAccount.username
				} else {
						
					if(userID != authAccount.username.toString()) {
						callback(HaleyStatus.error('auth userID ' + authAccount.username + ' does not match one set in message: ' + userID));
						return;
					}
				}
			
				String n = authAccount.name
				
				aimpMessage.userName = n != null ? n : authAccount.username
			}
		
		}
		
		String sid = aimpMessage.sessionID
		
		if(sid == null) {
			aimpMessage.sessionID = vitalService.sessionID
		} else {
			if(sid != sessionID) {
				callback(HaleyStatus.error('sessionID ' + sessionID + " does not match one set in message: " + sid));
				return
			}
		}
	
		ResultList rl = new ResultList()
		
		rl.addResult(aimpMessage)
	
		if(payload != null) {
			for(int i = 0 ; i < payload.size(); i++) {
				GraphObject g = payload.get(i)
				if(g == null) {
					callback(HaleyStatus.error("payload object cannot be null, #" + (i+1)));
					return;
				}
				if(g.URI == null) {
					callback(HaleyStatus.error("all payload objects must have URIs set, missing URI in object #" + (i+1) + " type: " + g.getClass().getCanonicalName()));
					return;
				}
				rl.addResult(g);
			}
		}
	
		String method = ''
		
		if( haleySession.isAuthenticated() ) {
			method = 'haley-send-message'
		} else {
			method = 'haley-send-message-anonymous'
		}
		
		boolean updateTimestamp = true
		
		if(aimpMessage instanceof UserLoggedIn || aimpMessage instanceof UserLoggedOut || aimpMessage instanceof UserLeftApp) {
			updateTimestamp = false
		} else if(aimpMessage instanceof HeartbeatMessage) {
			updateTimestamp = false
			if(this.lastActivityTimestamp != null) {
				aimpMessage.lastActivityTime = this.lastActivityTimestamp
			}
		}
 			
		this.vitalService.callFunction(method, [ message: rl ], { ResponseMessage sendRes ->
		
			if(sendRes.exceptionType) {
				
				if(retry == 0 && sendRes.exceptionType == "error_denied") {
								
					CachedCredentials cachedCredentials = cachedCredentials.get(haleySession.sessionID)
					
					if(cachedCredentials != null) {
						
						log.info("Session not found, re-authenticating...")
						
						haleySession.authAccount = null
						haleySession.authenticated = false
						haleySession.authSessionID = null
						vitalService.appSessionID = null
						
						authenticateSessionImpl(haleySession, cachedCredentials.username, cachedCredentials.password, false) { HaleyStatus status ->
							
							if(status.isOk()) {
								
								log.info("Successfully reauthenticated the session, sending the message.")
								
								sendMessageImpl(haleySession, aimpMessage, payload, retry+1, callback)
								
							} else {
							
								log.error("Reauthentication attempt failed: ${status.errorMessage}")
								
								callback(HaleyStatus.error(sendRes.exceptionType + ' - ' + sendRes.exceptionMessage))
								
								return
							
							}
							
						}
						
						return	
					}
					 
				}
				
				
				callback(HaleyStatus.error(sendRes.exceptionType + ' - ' + sendRes.exceptionMessage))
				
							
				return
			}
			
			ResultList sendRL = sendRes.response
		
			// send text message status: ERROR - error_denied - Session not found, session: Login_198a52b5-5e99-4626-ad17-f2ef923d7c1c
				
			if(sendRL.status.status != VitalStatus.Status.ok) {
				
				callback(HaleyStatus.error(sendRL.status.message))
				
				return
				
			}
			
			log.info("message sent successfully", sendRL.status.message);
		
			if(updateTimestamp) {
				lastActivityTimestamp = System.currentTimeMillis()
			}
			
			callback(HaleyStatus.ok())
			
			return
			
		})
			
	}
	
	public HaleyStatus sendMessage(HaleySession session, AIMPMessage message) {
		
		throw new Exception("Blocking version not implemented.")
		
		//		HaleyStatus status = new HaleyStatus()
		//		
		//		return status
		
		
	}
	
	public void sendMessage(HaleySession session, AIMPMessage message, Closure callback) {

		this.sendMessage(session, message, [], callback)
			
	}
	
	public HaleyStatus sendMessage(HaleySession session, AIMPMessage message, List<GraphObject> payload) {
	
		throw new Exception("Blocking version not implemented.")
		//		HaleyStatus status = new HaleyStatus()
		//		
		//		
		//		return status
	
	
	}

	// should callbacks be on a per-session basis?
	public HaleyStatus registerCallback(List<Class<? extends AIMPMessage>> messageTypes, boolean subclasses, Closure callback) {
	
		if(messageTypes == null || messageTypes.size() == 0) throw new Exception("Null or empty messageTypes list")
		
		//
		//	
		//	var e = this._checkSession(haleySession);
		//	if(e) {
		//		throw e
		//	}
	
		for( MessageHandler h : this.handlers) {
		
			if( h.callback == callback ) {
				log.warn("handler already registered ", callback);
				return HaleyStatus.error("handler already registered ");
			}
		}
	
		this.handlers.add(new MessageHandler(
			callback: callback,
			primaryClasses: messageTypes,
			classes: subclasses ? messageTypes : [] 
		))
			
		return HaleyStatus.ok();
	
	}
	
	/*
	 * Associates callbacks with different message types based on class defined in ontology
	 * 
	 * if subclasses = true, then the callback will apply to all subclasses of the message type(s)
	 * 
	 */
	
	public HaleyStatus registerCallback(Class<? extends AIMPMessage> messagetype, boolean subclasses, Closure callback) {
		
		return this.registerCallback([messagetype], subclasses, callback)
		
		
	}
	
	public HaleyStatus registerDefaultCallback(Closure callback) {
	
		//	var e = this._checkSession(haleySession);
		//	if(e) {
		//		throw e
		//	}
	
		if(callback == null) {
			if(this.defaultHandler == null) {
				return HaleyStatus.error("Default handler not set, cannot deregister.");
			} else {
				this.defaultHandler = null;
				return HaleyStatus.ok();
			}
		}
	
		if(this.defaultHandler != null && this.defaultHandler == callback) {
			return HaleyStatus.error("Default handler already set and equal to new one.");
		} else {
			this.defaultHandler = callback;
			return HaleyStatus.ok()
		}
		
	}
	
	public HaleyStatus registerRequestCallback(AIMPMessage aimpMessage, Closure callback) {
		
		//		var e = this._checkSession(haleySession);
		//		if(e) {
		//			throw e
		//		}
		
		if(aimpMessage == null) return HaleyStatus.error("null aimpMessage")
		if(aimpMessage.URI == null) return HaleyStatus.error("null aimpMessage.URI")
		if(callback == null) return HaleyStatus.error("null callback")
		Closure currentCB = this.requestHandlers[aimpMessage.URI];
		
		if(currentCB == null || currentCB != callback) {
			this.requestHandlers[aimpMessage.URI] = callback;
			return HaleyStatus.ok();
		} else {
			return HaleyStatus.error("This callback already set for this message.");
		}
		
	}
	
	public HaleyStatus deregisterCallback(Closure callback) {
		
		//		var e = this._checkSession(haleySession);
		//		if(e) {
		//			throw e
		//		}
		
		if(this.defaultHandler != null && this.defaultHandler == callback) {
			this.defaultHandler = null;
			return HaleyStatus.ok();
		}
		
		for( MessageHandler h : this.handlers ) {
			
			if(h.callback == callback) {
				
				this.handlers.remove(h)
				
				return HaleyStatus.ok();
			}
			
		}

		for(Iterator<Entry<String, Closure>> iterator = this.requestHandlers.entrySet().iterator(); iterator.hasNext();) {
			
			if( iterator.next().getValue() == callback ) {
				iterator.remove()
				return HaleyStatus.ok()
			}
			
		}
				
		return HaleyStatus.error("Callback not found");
		
	}
	
	public HaleyStatus uploadBinary(HaleySession session, Channel channel) {
		
		throw new Exception("not implemented yet.")
		//		HaleyStatus status = new HaleyStatus()
		//		
		//		
		//		return status
		
	}
	
	public void uploadBinary(HaleySession session, Channel channel, Closure callback) {
		
		throw new Exception("not implemented yet.")
		
	}
		
	public void uploadFile(HaleySession session, QuestionMessage questionMessage, FileQuestion fileQuestion, File file, Closure callback) {
	
		//		if(!scope == 'Public' || scope == 'Private') {
		//			callback(HaleyStatus.error("Invalid scope: " + scope + " , expected Public/Private"))
		//			return
		//		}
		
		String error = this._checkSession(session);
		if(error) {
			callback(HaleyStatus.error(error));
			return;
		}

		def executor = new HaleyFileUploadImplementation()
		executor.callback = callback
		executor.haleyApi = this
		executor.haleySession = session
		executor.questionMsg = questionMessage
		executor.fileQuestion = fileQuestion
		// executor.scope = scope
		executor.file = file
		executor.doUpload()
				
	}
	
	private final static Pattern s3URLPattern = Pattern.compile('^s3\\:\\/\\/([^\\/]+)\\/(.+)$', Pattern.CASE_INSENSITIVE)
	
	public String getFileNodeDownloadURL(HaleySession haleySession, FileNode fileNode) {
		
		String scope = fileNode.fileScope
		
		if(!scope) scope = 'public';
		
		if('PRIVATE' == scope.toUpperCase()) {
				
			return this.getFileNodeURIDownloadURL(haleySession, fileNode.URI);
			
		} else {
			
			//just convert s3 to public https link
			String fileURL = fileNode.fileURL
			Matcher matcher = s3URLPattern.matcher(fileURL);
			if(matcher.matches()) {
				
				String bucket = matcher.group(1)
				String key = matcher.group(2)
				
				//
				key = key.replace('%', '%25')
				key = key.replace('+', '%2B')
				
				String keyEscaped = key
				
				return 'https://' + bucket + '.s3.amazonaws.com/' + keyEscaped;
				
			}
			
			return fileURL;
			
		}
		
	}
	
	/**
	 * Returns the download URL for given file node URI
	 */
	public String getFileNodeURIDownloadURL(HaleySession haleySession, String fileNodeURI) {
	
		URL websocketURL = this.vitalService.url
		
		int port = websocketURL.getPort()
		boolean secure = websocketURL.getProtocol() == 'https'
		if(secure && port < 0) {
			port = 443
		} else if(port < 0){
			port = 80
		}
		
		String url = websocketURL.getProtocol() + "://" + websocketURL.getHost() 
		if(websocketURL.getPort() > 0) {
			url += ( ":" + websocketURL.getPort() )
		}
		
		url += ( '/filedownload?fileURI=' + URLEncoder.encode(fileNodeURI, 'UTF-8') )
		
		if(haleySession.isAuthenticated()) {
			url += ('&authSessionID=' + URLEncoder.encode(haleySession.getAuthSessionID(), 'UTF-8'))
		} else {
			url += '&sessionID=' + URLEncoder.encode(haleySession.getSessionID(), 'UTF-8');
		}

		return url;
		
		
	}
	
	public HaleyStatus downloadBinary(HaleySession session, String identifier, Channel channel) {
		
		throw new Exception("not implemented yet.")
		
		
	}
	
	
	
	public void downloadBinary(HaleySession session, String identifier, Channel channel, Closure callback) {
		
		throw new Exception("not implemented yet")
		
		
	}
	
	
	public Collection<AIMPChannel> listChannels(HaleySession session) {

		throw new Exception("Blocking version not implemented.")
		
		
	}

	public void listChannels(HaleySession session, Closure callback) {
		
		String error = this._checkSession(session)
		
		if(error) {
			callback(error, null);
			return;
		}

		log.info("ListChannels Session: " + session.toString())
		
		ListChannelsRequestMessage msg = new ListChannelsRequestMessage()
		
		msg.generateURI((VitalApp) null)

		// msg.userID = session.authAccount.username
			
		Closure requestCallback = { ResultList message ->
		
			callback(null, message.iterator(AIMPChannel.class).toList());
		
			//remove it always!
			return false;
		
		}

		HaleyStatus status = this.registerRequestCallback(msg, requestCallback)
			
		if( ! status.ok ) {
			callback('couldn\'t register request callback: ' + status.errorMessage, null);
			return;
		}
	
		// this.sendMessageWithRequestCallback(haleySession, aimpMessage, graphObjectsList, callback, requestCallback)
	
		this.sendMessage(session, msg, []) { HaleyStatus sendStatus->
		
			if(!sendStatus.ok) {
				
				String m = "Error when sending list channel request message: " + sendStatus.errorMessage
				
				log.error(m);
				
				callback(m, null);
				
				deregisterCallback(requestCallback)
			}
			
		}
		
	} 
	
	/**
	 * async operation
	 * callback called with String error, List<DomainModel>
	 */
	public void listServerDomainModels(Closure callback) {

		//the endpoint must also provide simple rest methods to validate domains
		URL websocketURL = this.vitalService.url
		
		int port = websocketURL.getPort()
		boolean secure = websocketURL.getProtocol() == 'https'
		if(secure && port < 0) {
			port = 443
		} else if(port < 0){
			port = 80
		}
		
		def options = [
			//			protocolVersion:"HTTP_2",
			ssl: secure,
			//			useAlpn:true,
			trustAll:true
		  ]
		
		
		HttpClient client = null
		
		def onFinish = { String error, List<DomainModel> results ->
			
			try {
				if(client != null) client.close()
			} catch(Exception e) {
				log.error(e.localizedMessage, e)
			}
			
			callback(error, results)
			
		}
		
		try {
			
			client = this.vitalService.vertx.createHttpClient(options);
			
			HttpClientRequest request = client.get(port, websocketURL.host, "/domains") { HttpClientResponse response ->

				if( response.statusCode() != 200 ) {
					onFinish("HTTP Status: " + response.statusCode() + " - " + response.statusMessage(), null)
					return
				}

				response.bodyHandler { Buffer body ->


					try {
						List<GraphObject> models = JSONSerializer.fromJSONArrayString(body.toString())
						
						List<DomainModel> filtered = []
						for(GraphObject o : models) {
							if(!(o instanceof DomainModel)) throw new Exception("Expected DomainModels only: " + models.size())
							filtered.add(o) 
						}
						onFinish(null, filtered)
						
					} catch(Exception e) {
						log.error(e.localizedMessage, e)
						onFinish(e.localizedMessage, null)
					}

				}
				
				response.exceptionHandler { Throwable ex ->
					log.error(ex.localizedMessage, ex)
					onFinish(ex.localizedMessage, null)
				}

			}
			
			request.exceptionHandler { Throwable ex ->
				
				log.error(ex.localizedMessage, ex)
				
				onFinish(ex.localizedMessage, null)
				
			}
			
			request.end()
			
		} catch(Exception e) {
			log.error(e.localizedMessage, e)
			onFinish(e.localizedMessage, null)
		}
	
	}
		
}
