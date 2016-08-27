package ai.haley.api.session

class HaleyStatus {

	boolean ok
	
	String errorMessage
	
	public static HaleyStatus error(String msg) {
		return new HaleyStatus(ok: false, errorMessage: msg)
	}
	
	public static HaleyStatus ok() {
		return new HaleyStatus(ok: true)
	}
	
	public String toString() {
		return "${ok ? 'OK' : 'ERROR'} - ${errorMessage}"
	}
	
}
