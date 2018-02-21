package ai.haley.api.sample

import io.vertx.core.Vertx
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
	
	
	private static Vertx vertx
	
	
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
	
		vertx = Vertx.vertx()
			
		VitalServiceAsyncWebsocketClient websocketClient = new VitalServiceAsyncWebsocketClient(vertx, app, 'endpoint.', endpointURL, 3, 3000)
		
		websocketClient.connect({ Throwable exception ->
			
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
			
		}, {Integer attempts ->
			System.err.println("FAILED, attempts: ${attempts}")
		})
		
		
	}
	
	static void onSessionReady() {
		
		haleyAPI.authenticateSession(haleySession, username, password) { HaleyStatus status ->
			
			println "auth status: ${status}"

			if(!status.ok) {
				return
			}
			
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
			
			if(channels.size() == 0) {
				System.err.println("No channels available")
				return
			}
			
			for(Channel ch : channels) {
				if(ch.name.equalTo('haley')) {
					System.out.println("Default haley channel found")
					channel = ch
				}
			}
			
			if(channel == null) {
				System.out.println("WARNIONG: default haley channel not found, using first from the list")
				channel = channels[0]
				return
			}

			System.out.println("Channel ${channel.name} - ${channel.URI}")
			
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

				vertx.close()				
				
			}
			
		}
		
		t.setDaemon(true)
		t.start()

		
	}	
}
