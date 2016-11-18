package ai.haley.api.sample

import io.vertx.groovy.core.Vertx
import ai.haley.api.HaleyAPI
import ai.haley.api.session.HaleySession
import ai.haley.api.session.HaleyStatus
import ai.vital.service.vertx3.websocket.VitalServiceAsyncWebsocketClient
import ai.vital.vitalservice.query.ResultList
import ai.vital.vitalsigns.model.VitalApp

import com.vitalai.aimp.domain.Channel
import com.vitalai.aimp.domain.HaleyTextMessage
import com.vitalai.aimp.domain.UserTextMessage
import com.vitalai.aimp.domain.AIMPMessage

class HaleyAPISample {

	static HaleyAPI haleyAPI
	
	static HaleySession haleySession
	
	static Channel channel
	
	static String username
	
	static String password
	
	def static main(args) {
		
		if(args.length != 4) {
			System.err.println("usage: <endpoint> <appID> <username> <password>")
			return
		}
		
		String endpointURL = args[0]
		println "Endpoint: ${endpointURL}"
		String appID = args[1]
		println "AppID: ${appID}"
		username = args[2]
		println "Username: ${username}"
		password = args[3]
		println "Password length: ${password.length()}"
		
		VitalApp app = VitalApp.withId(appID)
		
		VitalServiceAsyncWebsocketClient websocketClient = new VitalServiceAsyncWebsocketClient(Vertx.vertx(), app, 'endpoint.', endpointURL)
		
		websocketClient.connect() { Throwable exception ->
			
			if(exception) {
				exception.printStackTrace()
				return
			}
			
			haleyAPI = new HaleyAPI(websocketClient)
			
			println "Sessions: " + haleyAPI.getSessions().size()
			
			haleyAPI.openSession() { String errorMessage,  HaleySession session ->
				
				haleySession = session
				
				if(errorMessage) {
					throw new Exception(errorMessage)
				}
				
				println "Session opened ${session.toString()}"
				
				println "Sessions: " + haleyAPI.getSessions().size()
				
				onSessionReady()
				
			}
			
		}
		
		
	}
	
	static void onSessionReady() {
		
		haleyAPI.authenticateSession(haleySession, username, password) { HaleyStatus status ->
			
			println "auth status: ${status}"

			println "session: ${haleySession.toString()}"			
			
			
			onAuthenticated()
			
		}
		
	}
	
	static void onAuthenticated() {
			
		println "listing channels"
		
		haleyAPI.listChannels(haleySession) { String error, List<Channel> channels ->

			if(error) {
				System.err.println("Error when listing channels")
				return
			}			

			
			println "channels count: ${channels.size()}"
			
			channel = channels[0]
			
			onChannelObtained()
			
		}
			
			
	}
	
	static void onChannelObtained() {
		
		
		HaleyStatus rs = haleyAPI.registerCallback(AIMPMessage.class, true, { ResultList msg ->
			
			//HaleyTextMessage m = msg.first()
			
			AIMPMessage m = msg.first()
			
			println "HALEY SAYS: ${m?.text}"
			
		})
		
		println "Register callback status: ${rs}"
		
		UserTextMessage utm = new UserTextMessage()
		utm.text = "Whats your name?"
		utm.channelURI = channel.URI
		
		haleyAPI.sendMessage(haleySession, utm) { HaleyStatus sendStatus ->
			
			println "send text message status: ${sendStatus}"
		
			interactiveMode()
				
		}
		
		
	}

	static void interactiveMode() {
		
		println "INTERACTIVE MODE, 'quit' quits the program"
		
		Thread t = new Thread() {
			
			@Override
			public void run() {
				Scanner scanner = new Scanner(System.in)
				
				String lastLine = null
				
				while(lastLine != 'quit') {
					
					lastLine = scanner.nextLine()
					
					lastLine = lastLine.trim()
					
					if(lastLine != 'quit' && lastLine.length() > 0) {
						
						UserTextMessage utm = new UserTextMessage()
						utm.text = lastLine
						utm.channelURI = channel.URI
						
						haleyAPI.sendMessage(haleySession, utm) { HaleyStatus sendStatus ->
							
							println "send text message status: ${sendStatus}"
						
						}
						
					}
					
				}
				
				println "QUIT"
				
				haleyAPI.closeSession(haleySession)
			}
			
		}
		
		t.start()

		
	}	
}
