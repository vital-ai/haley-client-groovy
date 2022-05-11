package ai.haley.api

import org.slf4j.Logger
import org.slf4j.LoggerFactory;

import ai.vital.vitalsigns.VitalSigns;
import ai.vital.vitalsigns.model.DomainModel
import ai.vital.vitalsigns.ontology.VitalCoreOntology;;

class HaleyAPIDomainsValidation {

	private final static Logger log = LoggerFactory.getLogger(HaleyAPIDomainsValidation.class)
	
	/**
	 * 
	 * @param haleyApi
	 * @param failIfListElementsDifferent - if true it will also fail if there domains list list contain unique elements
	 * @param callback String error
	 * @return
	 */
	public static validateDomains(HaleyAPI haleyApi, boolean failIfListElementsDifferent, Closure callback) {
		
		def handler = { String error, List<DomainModel> models ->
			
			try {
				
				if(error) {
					callback("Error when listing server domain models: " + error)
					return
				}
				
				Map<String, DomainModel> localDomains = [:]
				Map<String, DomainModel> serverDomains = [:]
				for(DomainModel dm : models) {
					serverDomains.put(dm.URI, dm)
				}
				
				List<DomainModel> localDomainsList = VitalSigns.get().getDomainModels()  
				
				for(DomainModel dm : localDomainsList) {
					localDomains.put(dm.URI, dm)
				}
				
				if(failIfListElementsDifferent) {
					
					Set<String> localURIs = new HashSet<String>(localDomains.keySet()) 
					Set<String> serverURIs = new HashSet<String>(serverDomains.keySet())
					
					localURIs.removeAll(serverURIs)
					if(localURIs.size() > 0) {
						callback("The following domains are loaded only locally: " + localURIs)
						return 
					}
					
					localURIs = new HashSet<String>(localDomains.keySet())
					serverURIs.removeAll(localURIs)
					if(serverURIs.size() > 0) {
						callback("The following domains are loaded only on the server: " + serverURIs)
						return 
					}
					 
				}
				
				List<String> differentDomains = []
				
				for(DomainModel localDomain : localDomainsList) {
					
					DomainModel serverDomain = serverDomains.get(localDomain.URI)
					
					if(!failIfListElementsDifferent && serverDomain == null) {
						continue
					}
					
	//				if( localDomain.domainOWLHash.toString() != 
					
					String hash1 = localDomain.domainOWLHash
					String hash2 = serverDomain.domainOWLHash
					String v1 = localDomain.versionInfo
					String v2 = serverDomain.versionInfo

					if(hash1 != hash2) {
						
						differentDomains.add(localDomain.URI + " local hash: " + hash1 + " remote hash: " + hash2)
						continue
					}			
					
					if(v1 != v2) {
						differentDomains.add(localDomain.URI + " local version: " + v1 + " remote version: " + v2)
						continue
					}		
 					
				}
				
				if(differentDomains.size() > 0) {
					callback("Different domains detected [${differentDomains.size()}]: " + differentDomains)
					return
				}
				
				callback(null)
			
			} catch(Exception e) {
				
				HaleyAPIDomainsValidation.log.error(e.localizedMessage, e)
				
				callback("Internal error: " + e.localizedMessage)
				
			}
			
			
		}
		
		
		haleyApi.listServerDomainModels(handler)
		
	}
	
}
