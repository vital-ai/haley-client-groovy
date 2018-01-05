package ai.haley.api.session

class HaleyStatus {

	boolean ok
	
	String errorMessage
	
	//some operations provide result
	Object result
	
	public static HaleyStatus error(String msg) {
		return new HaleyStatus(ok: false, errorMessage: msg)
	}
	
	public static HaleyStatus ok() {
		return new HaleyStatus(ok: true)
	}
	
	public static HaleyStatus okWithResult(Object result) {
		return new HaleyStatus(ok: true, result: result)
	}
	
	public String toString() {
		return "${ok ? 'OK' : 'ERROR'} - ${errorMessage}"
	}
	
	
	
}
