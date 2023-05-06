package ai.haley.api.session

import ai.vital.domain.Login;

class HaleySession {

	boolean authenticated = false
	
	String authSessionID

	String sessionID
	
	Login authAccount
	
	@Override
	public String toString() {
		String s = "HaleySession sessionID: ${sessionID} authenticated ? ${authenticated}"
		if(authenticated) {
			s += " authSessionID: ${authSessionID} authAccount: ${authAccount?.toCompactString()}"
		}
		return s.toString()
	}
}
