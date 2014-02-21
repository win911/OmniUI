package net.floodlightcontroller.omniui;

import java.io.IOException;
import java.util.Map;

//
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.HAListenerTypeMarker;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IHAListener;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.staticflowentry.web.StaticFlowEntryWebRoutable;
import net.floodlightcontroller.storage.IResultSet;
import net.floodlightcontroller.storage.IStorageSourceListener;
import net.floodlightcontroller.storage.StorageException;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.openflow.util.HexString;
import org.openflow.util.U16;

//

import org.restlet.resource.Delete;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;

import net.floodlightcontroller.core.annotations.LogMessageCategory;
import net.floodlightcontroller.core.annotations.LogMessageDoc;
import net.floodlightcontroller.storage.IStorageSourceService;

import java.util.concurrent.Future;
import org.openflow.protocol.OFStatisticsRequest;
import java.util.concurrent.TimeUnit;
import org.openflow.protocol.statistics.*;
import org.openflow.protocol.OFPort;

/**
 * Pushes a static flow entry to the storage source
 * @author alexreimers
 *
 */
@LogMessageCategory("FlowModResource")
public class FlowModResource extends ServerResource {
    protected static Logger log = LoggerFactory.getLogger(FlowModResource.class);

	public static final String TABLE_NAME = "controller_FlowModtable";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_SWITCH = "switch_id";
    public static final String COLUMN_ACTIVE = "active";
    public static final String COLUMN_IDLE_TIMEOUT = "idle_timeout";
    public static final String COLUMN_HARD_TIMEOUT = "hard_timeout";
    public static final String COLUMN_PRIORITY = "priority";
    public static final String COLUMN_COOKIE = "cookie";
    public static final String COLUMN_WILDCARD = "wildcards";
    public static final String COLUMN_IN_PORT = "in_port";
    public static final String COLUMN_DL_SRC = "dl_src";
    public static final String COLUMN_DL_DST = "dl_dst";
    public static final String COLUMN_DL_VLAN = "dl_vlan";
    public static final String COLUMN_DL_VLAN_PCP = "dl_vlan_pcp";
    public static final String COLUMN_DL_TYPE = "dl_type";
    public static final String COLUMN_NW_TOS = "nw_tos";
    public static final String COLUMN_NW_PROTO = "nw_proto";
    public static final String COLUMN_NW_SRC = "nw_src"; // includes CIDR-style
                                                         // netmask, e.g.
                                                         // "128.8.128.0/24"
    public static final String COLUMN_NW_DST = "nw_dst";
    public static final String COLUMN_TP_DST = "tp_dst";
    public static final String COLUMN_TP_SRC = "tp_src";
    public static final String COLUMN_ACTIONS = "actions";
    public static String ColumnNames[] = { COLUMN_NAME, COLUMN_SWITCH,
            COLUMN_ACTIVE, COLUMN_IDLE_TIMEOUT, COLUMN_HARD_TIMEOUT,
            COLUMN_PRIORITY, COLUMN_COOKIE, COLUMN_WILDCARD, COLUMN_IN_PORT,
            COLUMN_DL_SRC, COLUMN_DL_DST, COLUMN_DL_VLAN, COLUMN_DL_VLAN_PCP,
            COLUMN_DL_TYPE, COLUMN_NW_TOS, COLUMN_NW_PROTO, COLUMN_NW_SRC,
            COLUMN_NW_DST, COLUMN_TP_DST, COLUMN_TP_SRC, COLUMN_ACTIONS };
	
	
    /**
     * Checks to see if the user matches IP information without
     * checking for the correct ether-type (2048).
     * @param rows The Map that is a string representation of
     * the static flow.
     * @reutrn True if they checked the ether-type, false otherwise
     */ 
	 
    private boolean checkMatchIp(Map<String, Object> rows) {
        boolean matchEther = false;
        String val = (String) rows.get(COLUMN_DL_TYPE);
        if (val != null) {
            int type = 0;
            // check both hex and decimal
            if (val.startsWith("0x")) {
                type = Integer.parseInt(val.substring(2), 16);
            } else {
                try {
                    type = Integer.parseInt(val);
                } catch (NumberFormatException e) { /* fail silently */}
            }
            if (type == 2048) matchEther = true;
        }

        if ((rows.containsKey(COLUMN_NW_DST) ||
                rows.containsKey(COLUMN_NW_SRC) ||
                rows.containsKey(COLUMN_NW_PROTO) ||
                rows.containsKey(COLUMN_NW_TOS)) &&
                (matchEther == false))
            return false;

        return true;
    }
	
	protected Map<String, Map<String, OFFlowMod>> entriesFromStorage = new ConcurrentHashMap<String, Map<String, OFFlowMod>>();
	static protected Map<String, Map<String, OFFlowMod>> entriesFromStorage2 = new ConcurrentHashMap<String, Map<String, OFFlowMod>>();
	
    /**
     * Takes a Static Flow Pusher string in JSON format and parses it into
     * our database schema then pushes it to the database.
     * @param fmJson The Static Flow Pusher entry in JSON format.
     * @return A string status message
     */
    @Post
    @LogMessageDoc(level="ERROR",
        message="Error parsing push flow mod request: {request}",
        explanation="An invalid request was sent to static flow pusher",
        recommendation="Fix the format of the static flow mod request")
    public String store(String fmJson) {
        IStorageSourceService storageSource =
                (IStorageSourceService)getContext().getAttributes().
                    get(IStorageSourceService.class.getCanonicalName());
					
		IFloodlightProviderService floodlightProvider =
                (IFloodlightProviderService)getContext().getAttributes().
                    get(IFloodlightProviderService.class.getCanonicalName());
		FlowModMethod.setprovider(floodlightProvider);
		
        Map<String, Object> rowValues;
        try {
            rowValues = FlowModMethod.jsonToStorageEntry(fmJson);
            String status = null;
            if (!checkMatchIp(rowValues)) {
                status = "Warning! Pushing a static flow entry that matches IP " +
                        "fields without matching for IP payload (ether-type 2048) will cause " +
                        "the switch to wildcard higher level fields.";
                log.error(status);
            } else {
                status = "Entry pushed";
            }
            storageSource.insertRowAsync(TABLE_NAME, rowValues);
			
			//
			FlowModMethod.parseRow(rowValues,entriesFromStorage,entriesFromStorage2);
			for (String switchid : entriesFromStorage.keySet()) {
				for (String entrynumber : entriesFromStorage.get(switchid).keySet())
				{
					if(entriesFromStorage.get(switchid).get(entrynumber) != null)
					{
						FlowModMethod.writeFlowModToSwitch(HexString.toLong(switchid),entriesFromStorage.get(switchid).get(entrynumber));
						if(trydelete!=true) status += isexitintable(floodlightProvider.getSwitch(HexString.toLong(switchid)),entriesFromStorage.get(switchid).get(entrynumber));
					}
				}
			}
			//
			
            if(trydelete==true){
				trydelete=false;
				if(removed==true){ removed=false; return ("{\"status\" : \"" + "Flow Deleted Successful" +"\"}"); }
				else return ("{\"status\" : \"" + "Flow Deleted Failed" +"\"}");
			}
			else return ("{\"status\" : \"" + status +"\"}");

        } catch (IOException e) {
            log.error("Error parsing push flow mod request: " + fmJson, e);
            return "{\"status\" : \"Error! Could not parse flod mod, see log for details.\"}";
        }
    }
	
	static void sendEntriesToSwitch(long switchId) {
        String stringId = HexString.toHexString(switchId);

        if ((entriesFromStorage2 != null) && (entriesFromStorage2.containsKey(stringId))) {
            Map<String, OFFlowMod> entries = entriesFromStorage2.get(stringId);
            ArrayList<String> sortedList = new ArrayList<String>(entries.keySet());
            Collections.sort(sortedList);
            for (String entryName : sortedList) {
                OFFlowMod flowMod = entries.get(entryName);
                if (flowMod != null) {
                    FlowModMethod.writeFlowModToSwitch(switchId, flowMod);
                }
            }
        }
    }
	static boolean removed = false, trydelete = false, addmod=false;
    static void setMsg(){
		removed = true;
	}
	
	public List<OFStatistics> value;
	public List<OFFlowStatisticsReply> flowList;
	
	public String isexitintable(IOFSwitch ofSwitch, OFFlowMod flowMod){
		OFStatisticsType type = OFStatisticsType.FLOW;
        if (ofSwitch == null) {
            return " error,no this switch." ;
        }
		Future<List<OFStatistics>> future;
		OFStatisticsRequest req = new OFStatisticsRequest();
		req.setStatisticType(type);
		int requestLength = req.getLengthU();
		
		OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
	    OFMatch match = new OFMatch();
		match.setWildcards(0xffffffff);
        specificReq.setMatch(match);
        specificReq.setOutPort(OFPort.OFPP_NONE.getValue());
    	specificReq.setTableId((byte) 0xff);
	    req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
	    requestLength += specificReq.getLength();
		req.setLengthU(requestLength);
		
		try{
			future = ofSwitch.queryStatistics(req);
			value = future.get(10,TimeUnit.SECONDS);
			
			flowList = new ArrayList<OFFlowStatisticsReply>(value.size());
			for(OFStatistics ofs: value){
				flowList.add( ((OFFlowStatisticsReply)ofs));
			}
			
			String msggg3="",msggg4="",msggg5="";
			for(OFFlowStatisticsReply flow: flowList){
				/*if(flow.getMatch().equals(flowMod.getMatch()) && flow.getCookie() == flowMod.getCookie()
                && flow.getPriority() == flowMod.getPriority() && flow.getActions().equals(flowMod.getActions())
				&& flow.getHardTimeout() == flowMod.getHardTimeout() && flow.getIdleTimeout() == flowMod.getIdleTimeout()){
					return " match OK.";
				}*/
				
				// All Match 
				String msggg="",msggg2="";
				msggg+=flow.getMatch();
				msggg2+=flowMod.getMatch();
				if(msggg.equals(msggg2)){	
					msggg="";msggg2="";
					msggg+=flow.getCookie();
					msggg2+=flowMod.getCookie();
					if(msggg.equals(msggg2)){	
						msggg="";msggg2="";
						msggg+=flow.getPriority();
						msggg2+=flowMod.getPriority();
						if(msggg.equals(msggg2)){
							msggg="";msggg2="";
							msggg+=flow.getActions();
							msggg2+=flowMod.getActions();
							if(msggg.equals(msggg2)||(msggg.equals("[]")&&msggg2.equals("null"))){
								addmod=false;
								return " OK.";	//MOD_ST ADD/MOD
							}
						}
					}	
				}
				
				// For Mod not all match
				if(addmod==true){
					OFMatch M1,M2;
					M1=flow.getMatch();
					M2=flowMod.getMatch();
					
					String pam="",pam2="";
					pam+=M1.getInputPort();
					pam2+=M2.getInputPort();
					if(!pam2.equals("0")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=HexString.toHexString(M1.getDataLayerDestination());
					pam2+=HexString.toHexString(M2.getDataLayerDestination());
					if(!pam2.equals("00:00:00:00:00:00")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=HexString.toHexString(M1.getDataLayerSource());
					pam2+=HexString.toHexString(M2.getDataLayerSource());
					if(!pam2.equals("00:00:00:00:00:00")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=M1.getDataLayerType();
					pam2+=M2.getDataLayerType();
					if(!pam2.equals("0")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=M1.getDataLayerVirtualLan();
					pam2+=M2.getDataLayerVirtualLan();
					//msggg3+=pam;	//
					//msggg4+=pam2;	//
					if(!pam2.equals("0")){
						if(!pam2.equals(pam)){
							if(!(pam2.equals("-1")&&pam.equals("0"))) continue;
						}
					}
					pam="";pam2="";
					pam+=intToIp(M1.getNetworkDestination());
					pam2+=intToIp(M2.getNetworkDestination());
					//msggg3+=pam;	//
					//msggg4+=pam2;	//
					if(!pam2.equals("0.0.0.0")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=M1.getNetworkProtocol();
					pam2+=M2.getNetworkProtocol();
					if(!pam2.equals("0")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=intToIp(M1.getNetworkSource());
					pam2+=intToIp(M2.getNetworkSource());
					if(!pam2.equals("0.0.0.0")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=M1.getNetworkTypeOfService();
					pam2+=M2.getNetworkTypeOfService();
					if(!pam2.equals("0")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=M1.getTransportDestination();
					pam2+=M2.getTransportDestination();
					if(!pam2.equals("0")){
						if(!pam2.equals(pam)) continue;
					}
					pam="";pam2="";
					pam+=M1.getTransportSource();
					pam2+=M2.getTransportSource();
					if(!pam2.equals("0")){
						if(!pam2.equals(pam)) continue;
					}
					// Actions
					pam="";pam2="";
					pam+=flow.getActions();
					pam2+=flowMod.getActions();
					if(!pam2.equals(pam)){
							if(!(pam.equals("[]")&&pam2.equals("null"))) continue;
						//return " Failed.";
					}
					return " OK."; //MOD
				}
			}
			addmod=false;
			//return " match Failed. "+msggg3+" "+msggg4+" "+msggg5;
			return " Failed.";
		}catch (Exception e){
			log.error("err = {}",e.toString());
			return "Exception error";
		}
	}
	private String intToIp(int i) {
        return ((i >> 24 ) & 0xFF) + "." +
               ((i >> 16 ) & 0xFF) + "." +
               ((i >>  8 ) & 0xFF) + "." +
               ( i        & 0xFF);
    }
}