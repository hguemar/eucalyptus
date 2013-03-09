/*************************************************************************
 * Copyright 2009-2013 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/

package com.eucalyptus.loadbalancing;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import javax.persistence.EntityTransaction;
import javax.validation.ConstraintViolationException;

import org.apache.log4j.Logger;
import com.eucalyptus.auth.principal.UserFullName;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionException;
import com.eucalyptus.loadbalancing.LoadBalancerListener.PROTOCOL;
import com.eucalyptus.loadbalancing.activities.LoadBalancerServoInstance;
import com.eucalyptus.util.Exceptions;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;

/**
 * @author Sang-Min Park
 */
public class LoadBalancers {
	private static Logger    LOG     = Logger.getLogger( LoadBalancers.class );
	public static LoadBalancer addLoadbalancer(UserFullName user, String lbName) throws LoadBalancingException
	{
		return LoadBalancers.addLoadbalancer(user,  lbName, null);
	}
	
	public static LoadBalancer getLoadbalancer(UserFullName user, String lbName) throws TransactionException{
		 final EntityTransaction db = Entities.get( LoadBalancer.class );
		 try {
			 final LoadBalancer lb = Entities.uniqueResult( LoadBalancer.named( user, lbName )); 
			 db.commit();
			 return lb;
		 }catch(NoSuchElementException ex){
			 db.rollback();
			 throw ex;
		 }catch(TransactionException ex){
			 db.rollback( );
			 LOG.error("failed to get the loadbalancer="+lbName, ex);
			 throw ex;
		 }
	}
	
	public static LoadBalancer addLoadbalancer(UserFullName user, String lbName, String scheme) throws LoadBalancingException {
		 final EntityTransaction db = Entities.get( LoadBalancer.class );
		 try {
		        try {
		        	if(Entities.uniqueResult( LoadBalancer.named( user, lbName )) != null)
		        		throw new LoadBalancingException(LoadBalancingException.DUPLICATE_LOADBALANCER_EXCEPTION);
		        } catch ( NoSuchElementException e ) {
		        	final LoadBalancer lb = LoadBalancer.newInstance(user, lbName);
		        	if(scheme!=null)
		        		lb.setScheme(scheme);
		        	Entities.persist( lb );
		          	db.commit( );
		          	return lb;
		        }
		    } catch ( Exception ex ) {
		    	db.rollback( );
		    	LOG.error("failed to persist a new loadbalancer", ex);
		    	throw new LoadBalancingException("Failed to persist a new load-balancer because of: " + ex.getMessage(), ex);
		  }
		  throw new LoadBalancingException("Failed to create a new load-balancer instance");
	}
	
	public static void deleteLoadbalancer(UserFullName user, String lbName) throws LoadBalancingException {
		final EntityTransaction db = Entities.get( LoadBalancer.class );
		try{
			final LoadBalancer lb = Entities.uniqueResult( LoadBalancer.named(user, lbName));	
			Entities.delete(lb);
			db.commit();
		}catch (NoSuchElementException e){
			db.rollback();
			throw new LoadBalancingException("No loadbalancer is found with name = "+lbName, e);
		}catch (Exception e){
			db.rollback();
			LOG.error("failed to delete a loadbalancer", e);
			throw new LoadBalancingException("Failed to delete the loadbalancer "+lbName, e);
		}
	}
	
	public static void createLoadbalancerListener(final String lbName, final UserFullName ownerFullName , final Collection<Listener> listeners) throws LoadBalancingException {
		  final Function<Void, LoadBalancingException> validator = new Function<Void, LoadBalancingException>(){
		        @Override
		        public LoadBalancingException apply( Void v ) {
		        	LoadBalancer lb = null;
		        	try{
		        		lb = LoadBalancers.getLoadbalancer(ownerFullName, lbName);
		        	}catch(Exception ex){
		    	    	return new AccessPointNotFoundException();
		    	    }
		        	
		        	try{
		        		for(Listener listener : listeners){
		        			if(!LoadBalancerListener.acceptable(listener))
		        				return new InvalidConfigurationRequestException();
			        		// check the listener 
			    			if(lb.hasListener(listener.getLoadBalancerPort().intValue())){
			    				LoadBalancerListener existing = lb.findListener(listener.getLoadBalancerPort().intValue());
			    				if(existing.getInstancePort() == listener.getInstancePort().intValue() &&
			    						existing.getProtocol().name().toLowerCase().equals(listener.getProtocol().toLowerCase()) &&
			    						(existing.getCertificateId()!=null && existing.getCertificateId().equals(listener.getSslCertificateId())))
			    					;
			    				else
			    					return new DuplicateListenerException();
			    			}
		        		}
		        	}catch(Exception ex){
		        		LOG.warn("duplicate check failed", ex);
						return new DuplicateListenerException();
		        	}
		        	return null;
		        	//need to check certificate 
		        }
		    };
		    
		    LoadBalancingException checkResult = Entities.asTransaction(LoadBalancer.class, validator).apply(null);
		    if(checkResult!=null)
		    	throw checkResult;
		    
		    final Predicate<Void> creator = new Predicate<Void>(){
		        @Override
		        public boolean apply( Void v ) {
		        	LoadBalancer lb = null;
		        	try{
		        		lb= LoadBalancers.getLoadbalancer(ownerFullName, lbName);
		        	}catch(Exception ex){
		    	    	LOG.warn("No loadbalancer is found with name="+lbName);    
		    	    	return false;
		    	    }
		        	
		        	for(Listener listener : listeners){
		        		// check the listener 
		    			try{	
		        			if(!lb.hasListener(listener.getLoadBalancerPort().intValue())){
		        				LoadBalancerListener.Builder builder = new LoadBalancerListener.Builder(lb, listener.getInstancePort().intValue(), 
		            					listener.getLoadBalancerPort().intValue(), LoadBalancerListener.PROTOCOL.valueOf(listener.getProtocol().toUpperCase()));
		            			if(!Strings.isNullOrEmpty(listener.getInstanceProtocol()))
		            				builder.instanceProtocol(PROTOCOL.valueOf(listener.getInstanceProtocol()));
		            			
		            			if(!Strings.isNullOrEmpty(listener.getSslCertificateId()))
		            				builder.withSSLCerntificate(listener.getSslCertificateId());
		            			Entities.persist(builder.build());
		        			}
		    			}catch(Exception ex){
		    				LOG.warn("failed to create the listener object", ex);
		    			}
		        	}
		        	return true;
		        }
		    };
		    Entities.asTransaction(LoadBalancerListener.class, creator).apply(null);
	}
	
	public static void addZone(final String lbName, final UserFullName ownerFullName, final Collection<String> zones) throws LoadBalancingException{
		// TODO: SPARK: validate the zones
	   	LoadBalancer lb = null;
    	try{
    		lb = LoadBalancers.getLoadbalancer(ownerFullName, lbName);
    	}catch(Exception ex){
	    	throw new AccessPointNotFoundException();
	    }
		final EntityTransaction db = Entities.get( LoadBalancerZone.class );
		for(String zone : zones){
    		// check the listener 
			try{
				Entities.uniqueResult(LoadBalancerZone.named(lb, zone));
			}catch(NoSuchElementException ex){
				final LoadBalancerZone newZone = LoadBalancerZone.newInstance(lb, zone);
				Entities.persist(newZone);
				db.commit();
			}catch(Exception ex){
				db.rollback();
				LOG.error("failed to persist the zone "+zone, ex);
			}
		}  
	}
	
	public static void removeZone(final String lbName, final UserFullName ownerFullName, final Collection<String> zones) throws LoadBalancingException{
	 	LoadBalancer lb = null;
    	try{
    		lb = LoadBalancers.getLoadbalancer(ownerFullName, lbName);
    	}catch(Exception ex){
	    	throw new AccessPointNotFoundException();
	    }
		final EntityTransaction db = Entities.get( LoadBalancerZone.class );
		for(String zone : zones){
			try{
				final LoadBalancerZone exist = Entities.uniqueResult(LoadBalancerZone.named(lb, zone));
				Entities.delete(exist);
				db.commit();
			}catch(NoSuchElementException ex){
				db.rollback();
				LOG.debug(String.format("zone %s not found for %s", zone, lbName));
			}catch(Exception ex){
				db.rollback();
				LOG.error("failed to delete the zone "+zone, ex);
			}
		}
	}
	
	public static LoadBalancerZone findZone(final LoadBalancer lb, final String zoneName){
		final EntityTransaction db = Entities.get(LoadBalancerZone.class);
		try{
			final LoadBalancerZone exist = Entities.uniqueResult(LoadBalancerZone.named(lb, zoneName));
			db.commit();
			return exist;
		}catch(NoSuchElementException ex){
			db.rollback();
			throw ex;
		}catch(Exception ex){
			db.rollback();
			throw Exceptions.toUndeclared(ex);
		}
	}
	
	public static LoadBalancerDnsRecord getDnsRecord(final LoadBalancer lb) throws LoadBalancingException{
		/// create the next dns record
		final EntityTransaction db = Entities.get( LoadBalancerDnsRecord.class );
		try{
			LoadBalancerDnsRecord exist = Entities.uniqueResult(LoadBalancerDnsRecord.named(lb.getDisplayName()));
			db.commit();
			return exist;
		}catch(NoSuchElementException ex){
			final LoadBalancerDnsRecord newRec = 
					LoadBalancerDnsRecord.named(lb.getDisplayName());
			newRec.setLoadbalancer(lb);
			Entities.persist(newRec);
			db.commit();
			return newRec;
		}catch(Exception ex){
			throw new LoadBalancingException("failed to query dns record", ex);
		}
	}
	
	public static void deleteDnsRecord(final LoadBalancerDnsRecord dns) throws LoadBalancingException{
		final EntityTransaction db = Entities.get( LoadBalancerDnsRecord.class );
		try{
			LoadBalancerDnsRecord exist = Entities.uniqueResult(dns);
			Entities.delete(exist);
			db.commit();
		}catch(NoSuchElementException ex){
			db.rollback();
		}catch(Exception ex){
			db.rollback();
			throw new LoadBalancingException("failed to delete dns record", ex);
		}
	}
	
	public static LoadBalancerServoInstance lookupServoInstance(String instanceId) throws LoadBalancingException {
		final EntityTransaction db = Entities.get( LoadBalancerDnsRecord.class );
		try{
			LoadBalancerServoInstance sample = LoadBalancerServoInstance.named(instanceId);
			final LoadBalancerServoInstance exist = Entities.uniqueResult(sample);
			db.commit();
			return exist;
		}catch(NoSuchElementException ex){
			db.rollback();
			throw ex;
		}catch(Exception ex){
			db.rollback();
			throw new LoadBalancingException("failed to query servo instances");
		}
	}
}
