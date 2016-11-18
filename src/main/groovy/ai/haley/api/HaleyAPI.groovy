package ai.haley.api

import ai.haley.api.HaleyAPI.MessageHandler;
import ai.haley.api.session.HaleySession
import ai.haley.api.session.HaleyStatus
import ai.vital.domain.Login
import ai.vital.domain.UserSession;
import ai.vital.service.vertx3.binary.ResponseMessage
import ai.vital.service.vertx3.websocket.VitalServiceAsyncWebsocketClient
import ai.vital.vitalservice.VitalStatus;
import ai.vital.vitalservice.query.ResultList
import ai.vital.vitalsigns.model.GraphObject;
import ai.vital.vitalsigns.model.VitalApp
import groovy.json.JsonOutput

import com.hp.hpl.jena.rdf.arp.JenaHandler;
import com.vitalai.aimp.domain.AIMPMessage
import com.vitalai.aimp.domain.Channel as AIMPChannel
import com.vitalai.aimp.domain.ListChannelsRequestMessage;
import com.vitalai.aimp.domain.UserLoggedIn

import java.nio.channels.Channel
import java.util.Map.Entry

import org.slf4j.Logger
import org.slf4j.LoggerFactory;

import jsr166y.ForkJoinPool 

import groovyx.gpars.GParsPool

//import java.util.concurrent.Executors


/*
 * Haley API
 * 
 * 
 * Includes synchronous and asynchronous calls
 * 
 * Multiple instances of HaleyAPI may be instantiated.  Howver, there is an underlying
 * singleton for vitalsigns, so domain classes will be shared across all instances.
 * 
 * The initial version may consider a single endpoint only.
 * 
 * There may be a conflict when opening multiple endpoints with conflicting domains.
 * This can throw an exception and not open the session.
 * 
 * 
 */
class HaleyAPI {
	
	
	
//	public final static String GROOVY_REGISTER_STREAM_HANDLER = 'groovy-register-stream-handler';
	
//	public final static String GROOVY_UNREGISTER_STREAM_HANDLER = 'groovy-unregister-stream-handler';
	
//	public final static String GROOVY_LIST_STREAM_HANDLERS = 'groovy-list-stream-handlers';
	
	public final static String VERTX_STREAM_SUBSCRIBE = 'vertx-stream-subscribe';
	
	public final static String VERTX_STREAM_UNSUBSCRIBE = 'vertx-stream-unsubscribe';
	
	
	private final static Logger log = LoggerFactory.getLogger(HaleyAPI.class)
	
	private static class MessageHandler {
		
		Closure callback
		
		List<Class<? extends AIMPMessage>> primaryClasses = []
		
		List<Class<? extends AIMPMessage>> classes = []
		
	}
	
//	private List<HaleySession> sessions = []
	
	private HaleySession haleySessionSingleton

	private VitalServiceAsyncWebsocketClient vitalService
	
	//service sessionID	
	private String sessionID
	
	private String streamName = 'haley'
	
	
	private List<MessageHandler> handlers = []

	//requestURI -> callback
	private Map<String, Closure> requestHandlers = [:]
	
	private Map<String, Closure> currentHandlers = [:]
	
	Closure defaultHandler = null;
	
	Closure handlerFunction = null;
	
	
	Map<String, Closure> registeredHandlers = [:]
	
	
	//private final def mainPool = Executors.newFixedThreadPool(10)
	
	private ForkJoinPool mainPool
	
	private syncdomains = false
	
	
	private String _checkSession(HaleySession haleySession) {
		
		if(this.haleySessionSingleton == null) return 'no active haley session found';
		
		if(this.haleySessionSingleton != haleySession) return 'unknown haley session';
		
		return null;
		
	}
	
	private void _registerStreamHandler(String streamName, Closure handlerFunction, Closure callback) {
	
		if(streamName == null) {
			callback("No 'streamName' param");
			return;
		}
	
		if(handlerFunction == null) {
			callback("No 'handlerFunction' param");
			return;
		}
	
		if( this.registeredHandlers[streamName] != null ) {
			callback("Handler for stream " + streamName + " already registered.");
			return;
		}
	
		this.registeredHandlers[streamName] = handlerFunction;
	
		callback(null)
		
	}
	
	//nodejs style callback
	private void _streamSubscribe(String streamName, Closure callback) {
		
		//first check if we are able to
		
		Closure currentHandler = this.registeredHandlers[streamName];
		
		if(currentHandler == null) {
			callback("No handler for stream " + streamName + " registered");
			return;
		}
		
		Closure activeHandler = this.currentHandlers[streamName]
		
		if(activeHandler != null) {
			callback("Handler for stream " + streamName + " already subscribed");
			return;
		}
		
		//first call the server side, on success register

		vitalService.callFunction(VERTX_STREAM_SUBSCRIBE, [streamNames: [streamName], sessionID: this.sessionID]) { ResponseMessage res ->
			
			if(res.exceptionType) {
				callback(res.exceptionType + ' - ' + res.exceptionMessage)
				return
			}
			
			
			//				  type: 'register',
			//				  address: address,
			//				  headers: mergeHeaders(this.defaultHeaders, headers)
			
			String address = 'stream.'+ this.sessionID
			
			vitalService.streamCallbacksMap.put(address, currentHandler)
			
			vitalService.webSocket.writeFinalTextFrame(JsonOutput.toJson([
				type: 'register',
				address: address,
				headers: [:]
			]))
//			/**
//			 * Register a new handler
//			 *
//			 * @param {String} address
//			 * @param {Object} [headers]
//			 * @param {Function} callback
//			 */
//			EventBus.prototype.registerHandler = function (address, headers, callback) {
//			  // are we ready?
//			  if (this.state != EventBus.OPEN) {
//				throw new Error('INVALID_STATE_ERR');
//			  }
//		  
//			  if (typeof headers === 'function') {
//				callback = headers;
//				headers = {};
//			  }
//		  
//			  // ensure it is an array
//			  if (!this.handlers[address]) {
//				this.handlers[address] = [];
//				// First handler for this address so we should register the connection
//				this.sockJSConn.send(JSON.stringify({
//				  type: 'register',
//				  address: address,
//				  headers: mergeHeaders(this.defaultHeaders, headers)
//				}));
//			  }
//		  
//			  this.handlers[address].push(callback);
			
			
			ResultList rl = res.response
			
			if(rl.status.status != VitalStatus.Status.ok) {
				callback("ERROR: " + rl.status.message)
				return
			}
			
			
			//register stream handler
//			vitalService.we
			
			currentHandlers[streamName] = currentHandler
			
			callback(null)
			
		}
		
		/*		
		var _this = this;
		
		this.callMethod('callFunction', args, function(successRL){
			
			if(!_this.eventbusListenerActive) {
				
				_this.eventbusHandler = _this.createNewHandler();
				_this.eb.registerHandler('stream.'+ _this.sessionID, _this.eventbusHandler);
				_this.eventbusListenerActive = true;
			}
			
			
			_this.currentHandlers[streamName] = currentHandler;
			
			successCB({
				_type: 'ai.vital.vitalservice.query.ResultList',
				status: {
					_type: 'ai.vital.vitalservice.VitalStatus',
					status: 'ok',
					message: 'Successfully Subscribe to stream ' + streamName
				}
			});
			
		}, function(errorResponse){
			errorCB(errorResponse);
		});
		*/
		
		
	}
	
	//nodejs callback
	private void _sendLoggedInMsg(Closure callback) {

		this.sendMessage(this.haleySessionSingleton, new UserLoggedIn(), []) { HaleyStatus status ->
			
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
//		});
	}
	
	// init a new instance
	// default is to use only locally available domains
	
	public HaleyAPI(VitalServiceAsyncWebsocketClient websocketClient) {
		
		this.vitalService = websocketClient
		
		this.vitalService.reconnectHandler = { Void v -> 
			
			log.info("Reconnecting haley api - re-subscribing handlers")
			
			currentHandlers.clear();
			
			for(Entry<String, Closure> e : registeredHandlers.entrySet()) {
				
				_streamSubscribe(e.getKey()) { String subscribeError ->
					
					if(subscribeError) {
						log.error("Couldn't resubscribe to stream: ${e.getKey()} - exiting: " + subscribeError)
						vitalService.close() { ResponseMessage res->
							
						}
						return
					}
					
					log.info('resubscribed to ' + this.streamName);
					
				}
			}
			
		}
		
		mainPool = new ForkJoinPool()
		
		this.syncdomains = false
		
	}
	
	
	/*
	 * utility classes to test parallelization of calls
	 * 
	 */
	
	public int getActiveThreadCount() {
		
		return mainPool.getActiveThreadCount()
		
	}
	
	public boolean isQuiescent() {
		
		return mainPool.isQuiescent()
		
	}
	
	
	// open session to haley service
	public HaleySession openSession() {
	
		throw new Exception("Blocking version not implemented yet")
		
//		HaleySession session = new HaleySession()
//		
//		sessions.add(session)
//		
//		return session
		
	
	}
	
	// open session to local haley server, given endpoint
	public HaleySession openSession(String endpoint) {
		
		throw new Exception("Blocking version not implemented yet")
//		HaleySession session = new HaleySession()
//		
//		sessions.add(session)
//		
//		return session
		
	}
	
	private void _streamHandler(ResultList msgRL) {

		AIMPMessage m = msgRL.first();
	
		log.info("Stream " + this.streamName + " received message: {} payload size {}", m.getClass().getCanonicalName(), msgRL.results.size() - 1);
		
		int c = 0;
	
		Class<? extends AIMPMessage> type = m.getClass();
	
		
		//requestURI handler
		String requestURI = m.requestURI
	
		if(requestURI != null) {
		
			Closure h = this.requestHandlers[requestURI];
		
			if(h != null) {
			
				log.info("Notifying requestURI handler", requestURI);
			
				Object cbRes = h(msgRL);
			
				if(cbRes != null && cbRes == false) {
				
					log.info("RequestURI handler returned false, unregistering");
				
					this.requestHandlers.remove(requestURI);
				
				} else {
				
					log.info("RequestURI handler returned non-false, still regsitered");
				
				}
			
				return;
			
			}
		
		}
		
		//primary classes
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
			
			log.info("Notifying default handler");
			
			this.defaultHandler(msgRL);
			
			c++
			
			return;
		}
		
		
		log.info("Notified " + c + " msg handlers");

	}
	
	//nodejs callback style: String error, HaleySession sessionObject 
	public void openSession(Closure callback) {
	
		
		this.sessionID = UUID.randomUUID().toString()
		
		
//		HaleySession session = new HaleySession()
//		
//		sessions.add(session)
//		
//		callback.call(session)
			
		if(this.haleySessionSingleton != null) {
			callback('active session already detected', null);
			return;
		}

		this.handlerFunction = { ResponseMessage res ->
			
			if(res.exceptionType) {
				log.error("Received stream error: ${res.exceptionType} - ${res.exceptionMessage}")
				return
			}
			
			ResultList rl = res.response
			
			if(rl.status.status != VitalStatus.Status.ok) {
				log.error("Received stream error: ${rl.status.message}")
				return
			}
			
			log.info("Message received: " + rl)
			
			_streamHandler(rl)
			
		}
		
		log.info('subscribing to stream ', this.streamName);
		
		_registerStreamHandler(this.streamName, this.handlerFunction) { String registerError ->
			
			if(registerError) {
				callback(registerError, null)
				return
			}
			
			log.info('registered handler to ' + this.streamName);
			
			_streamSubscribe(this.streamName) { String subscribeError ->
					
				if(subscribeError) {
					log.error(subscribeError)
					callback(subscribeError, null)
					return
				}
				
				log.info('subscribed to ' + this.streamName);
				
				//in groovy session cookie is unavailable
				this.haleySessionSingleton = new HaleySession(sessionID: this.sessionID)

				callback(null, this.haleySessionSingleton)				
				
			}
			
		}
		
		/*
		log.info('subscribing to stream ', this.streamName);
			
//			var _this = this;
//		
//			this.handlerFunction = function(msgRL){
//				_this._streamHandler(msgRL);
//			}
			
			//first register stream handler
			this.vitalService.callFunction(VitalService.JS_REGISTER_STREAM_HANDLER, {streamName: this.streamName, handlerFunction: this.handlerFunction}, function(succsessObj){
				
				console.log('registered handler to ' + _this.streamName, succsessObj);
				
				_this.vitalService.callFunction(VitalService.VERTX_STREAM_SUBSCRIBE, {streamName: _this.streamName}, function(succsessObj){
					
					console.log("subscribed to stream " + _this.streamName, succsessObj);
					
					//session opened
					_this.haleySessionSingleton = new HaleySession(_this);
					
					if(_this.haleySessionSingleton.isAuthenticated()) {
						
						_this._sendLoggedInMsg(function(error){
							
							console.log("LoggedIn msg sent successfully");
							
							if(error) {
								callback(error);
							} else {
								callback(null, _this.haleySessionSingleton);
							}
							
						});
						
					} else {
						
						callback(null, _this.haleySessionSingleton);
						
					}
					
					
					
				}, function(errorObj) {
					
					console.error("Error when subscribing to stream", errorObj);
					
					callback(errorObj);
					
				});
		
				
			}, function(error){
		
				console.error('couldn\'t register messages handler', error);
				
				callback(error);
				
			});
			*/
	
	}
	
	
	public HaleyStatus closeSession(HaleySession session) {
		
		throw new Exception("Blocking version not implemented yet")
		
//		HaleyStatus status = new HaleyStatus()
//		
//		
//		sessions.remove(session)
//		
//		return status
		
	}
	
	public void closeSession(HaleySession session, Closure callback) {
	
		throw new Exception("not implemented yet")
		
//		HaleyStatus status = new HaleyStatus()
//		
//		sessions.remove(session)
//		
//		callback.call(status)
		
	}
	
	
	public HaleyStatus closeAllSessions() {
		
		throw new Exception("Blocking version not implemented yet")
		
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
	
		throw new Exception("not implemented yet")
		
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
			return [this.haleySessionSingleton]
		} else {
			return []
		}
		
//		return sessions.asImmutable()
		
	}
	
	
	public HaleyStatus authenticateSession(HaleySession session, String username, String password) {

		throw new Exception("Blocking version not implemented yet") 
				
//		HaleyStatus status = new HaleyStatus()
//		return status
		
	}
	
	public void authenticateSession(HaleySession session, String username, String password, Closure callback) {
	
		String error = this._checkSession(session);
		if(error) {
			callback(HaleyStatus.error(error));
			return;
		}
		
		if(session.isAuthenticated()) {
			callback(HaleyStatus.error('session already authenticated'));
			return;
		}
		
		this.vitalService.callFunction('vitalauth.login', [loginType: 'Login', username: username, password: password]) { ResponseMessage loginRes ->
				
			if(loginRes.exceptionType) {
				callback(HaleyStatus.error("Logging in exception: ${loginRes.exceptionType} - ${loginRes.exceptionMessage}"))
				return
			}
			
			ResultList res = loginRes.response
			
			if(res.status.status != VitalStatus.Status.ok) {
				callback(HaleyStatus.error("Logging in failed: ${res.status.message}"))
				return
			}
			
			log.info("auth success: ");
	
			//logged in successfully keep session for further requests
			UserSession userSession = res.iterator(UserSession.class).next();
			if(session == null) {
				callback(HaleyStatus.error("No session object in positive login response"))
				return
			}
			
			Login userLogin = res.iterator(Login.class).next();
			
			if(userLogin == null) {
				callback(HaleyStatus.error("No login object in positive login response"))
				return
			}
			
			log.info("Session obtained: ${userSession.sessionID}")
			
			//set it in the client for future requests
			session.authSessionID = userSession.sessionID
			session.authAccount = userLogin
			session.authenticated = true
			
			//appSessionID must be set in order to send auth messages
			vitalService.appSessionID = userSession.sessionID
			
			_sendLoggedInMsg() { String sendError ->
				
				if(sendError) {
					callback(HaleyStatus.error(sendError))
				} else {
					callback(HaleyStatus.ok())
					
				}
				
			}
			
		}
		
}
	
	
	public HaleyStatus unauthenticateSession(HaleySession session) {
		
		throw new Exception("blocking version not implemented yet")
//		HaleyStatus status = new HaleyStatus()
//		
//		
//		
//		return status
		
		
	}
	
	public void unauthenticateSession(HaleySession session, Closure callback) {
	
		throw new Exception("not implemented yet")
		
//		HaleyStatus status = new HaleyStatus()
//		
//		
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
	
	
	//haley status callback
	public void sendMessage(HaleySession haleySession, AIMPMessage aimpMessage, List<GraphObject> payload, Closure callback) {

		if(aimpMessage == null) {
			callback(HaleyStatus.error("aimpMessage must not be null"));
			return;
		}
		
		if(aimpMessage.URI == null) {
			aimpMessage.generateURI((VitalApp) null)
		}
		
		String sessionID = haleySession.getSessionID();
	
		Login authAccount = haleySession.getAuthAccount();
		
		if( authAccount != null ) {
		
			String userID = aimpMessage.userID
		
			if(userID == null) {
				aimpMessage.userID = authAccount.username
			} else {
			
				if(userID != authAccount.username.toString()) {
					callback(HaleyStatus.error('auth userID ' + authAccount.get('username') + ' does not match one set in message: ' + userID));
					return;
				}
			}
		
			String n = authAccount.name
			aimpMessage.userName = n != null ? n : authAccount.username
		
		}
		
	
		String sid = aimpMessage.sessionID
		if(sid == null) {
			aimpMessage.sessionID = this.sessionID
		} else {
			if(sid != sessionID) {
				callback(HaleyStatus.error('auth sessionID ' + sessionID + " does not match one set in message: " + sid));
			}
		}
	
		ResultList rl = new ResultList()
		rl.addResult(aimpMessage);
	
		if(payload != null) {
			for(GraphObject g : payload) {
				rl.addResult(g);
			}
		}
	
		
		this.vitalService.callFunction('haley-send-message', [message: rl]) { ResponseMessage sendRes ->
		
			if(sendRes.exceptionType) {
				callback(HaleyStatus.error(sendRes.exceptionType + ' - ' + sendRes.exceptionMessage))
				return
			}
			
			ResultList sendRL = sendRes.response
			
			if(sendRL.status.status != VitalStatus.Status.ok) {
				callback(HaleyStatus.error(sendRL.status.message))
				return
			}
			
			log.info("message sent successfully", sendRL.status.message);
		
			callback(HaleyStatus.ok());
		
		}
		
	}
	
	
	public HaleyStatus sendMessage(HaleySession session, AIMPMessage message) {
		
		throw new Exception("Blocking version not implemented")
		
//		HaleyStatus status = new HaleyStatus()
//		
//		return status
		
		
	}
	
	public void sendMessage(HaleySession session, AIMPMessage message, Closure callback) {

		this.sendMessage(session, message, [], callback)
		
		
	}
	
	
	
	public HaleyStatus sendMessage(HaleySession session, AIMPMessage message, List<GraphObject> payload) {
	
		throw new Exception("Blocking version not implemented")
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
				return HaleyStatus.error("Default handler not set, cannot deregister");
			} else {
				this.defaultHandler = null;
				return HaleyStatus.ok();
			}
		}
	
		if(this.defaultHandler != null && this.defaultHandler == callback) {
			return HaleyStatus.error("Default handler already set and equal to new one");
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
		
		if(aimpMessage == null) throw "null aimpMessage";
		if(aimpMessage.URI == null) throw "null aimpMessage.URI";
		if(callback == null) throw "null callback";
		Closure currentCB = this.requestHandlers[aimpMessage.URI];
		
		if(currentCB == null || currentCB != callback) {
			this.requestHandlers[aimpMessage.URI] = callback;
			return HaleyStatus.ok();
		} else {
			return HaleyStatus.error("This callback already set for this message");
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
		
		throw new Exception("not implemented yet")
//		HaleyStatus status = new HaleyStatus()
//		
//		
//		return status
		
	}
	
	public void uploadBinary(HaleySession session, Channel channel, Closure callback) {
	
		throw new Exception("not implemented yet")
		
//		HaleyStatus status = new HaleyStatus()
//		
//		
//		callback.call(status)
	
	}
	
	public HaleyStatus downloadBinary(HaleySession session, String identifier, Channel channel) {
		
		throw new Exception("not implemented yet")
		
//		HaleyStatus status = new HaleyStatus()
//		
//		return status
	}
	
	/*
	 * Includes a test of using GPars for parallelization of calls.
	 * This should be used for all HaleyCallback cases
	 */
	
	public void downloadBinary(HaleySession session, String identifier, Channel channel, Closure callback) {
		
		throw new Exception("not implemented yet")
		
//		GParsPool.withExistingPool(mainPool) {  
//		
//		
//			{ ->
//			
//				HaleyStatus status = new HaleyStatus()
//			
//				for(n in 1..5) {
//			
//					sleep(1000)
//			
//					callback.downloadStatus(status)
//			
//				}
//			
//			}.async().call()
//			
//		}
	}
	
	
	public Collection<AIMPChannel> listChannels(HaleySession session) {

		throw new Exception("Blocking version not implemented")
		
//		List<AIMPChannel> channels = []
//		
//		return channels
		
	}

	//nodejs <error, list> type	 
	public void listChannels(HaleySession session, Closure callback) {
		
		String error = this._checkSession(session);
		if(error) {
			callback(error, null);
			return;
		}

		ListChannelsRequestMessage msg = new ListChannelsRequestMessage()
		msg.generateURI((VitalApp) null)

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
	
//	this.sendMessageWithRequestCallback(haleySession, aimpMessage, graphObjectsList, callback, requestCallback)
	
		this.sendMessage(session, msg, []) { HaleyStatus sendStatus->
		
			if(!sendStatus.ok) {
				
				String m = "Error when sending list channel request message: " + sendStatus.errorMessage
				
				log.error(m);
				
				callback(m, null);
				
				deregisterCallback(requestCallback)
			}
			
		}
		
	} 
	
}
