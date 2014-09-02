// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.log4j.Logger;
import org.cloud.network.router.deployment.RouterDeploymentDefinition;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.BumpUpPriorityCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.configuration.Config;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.maint.Version;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.VirtualNetworkApplianceService;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.UserIpv6AddressDao;
import com.cloud.network.router.VirtualRouter.RedundantState;
import com.cloud.network.router.VirtualRouter.Role;
import com.cloud.network.vpn.Site2SiteVpnManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineName;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

@Local(value = { NetworkHelper.class })
public class NetworkHelperImpl implements NetworkHelper {

    private static final Logger s_logger = Logger.getLogger(NetworkHelperImpl.class);

    @Inject
    protected NicDao _nicDao;
    @Inject
    private NetworkDao _networkDao;
    @Inject
    protected DomainRouterDao _routerDao;
    @Inject
    private AgentManager _agentMgr;
    @Inject
    private AlertManager _alertMgr;
    @Inject
    protected NetworkModel _networkModel;
    @Inject
    private VirtualMachineManager _itMgr;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private Site2SiteVpnManager _s2sVpnMgr;
    @Inject
    private HostDao _hostDao;
    @Inject
    private VolumeDao _volumeDao;
    @Inject
    private ServiceOfferingDao _serviceOfferingDao;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private ClusterDao _clusterDao;
    @Inject
    protected IPAddressDao _ipAddressDao;
    @Inject
    private IpAddressManager _ipAddrMgr;
    @Inject
    private UserIpv6AddressDao _ipv6Dao;
    @Inject
    private RouterControlHelper _routerControlHelper;
    @Inject
    private ConfigurationDao _configDao;
    @Inject
    protected NetworkOrchestrationService _networkMgr;

    protected final Map<HypervisorType, ConfigKey<String>> hypervisorsMap = new HashMap<>();

    @PostConstruct
    protected void setupHypervisorsMap() {
        hypervisorsMap.put(HypervisorType.XenServer, VirtualNetworkApplianceManager.RouterTemplateXen);
        hypervisorsMap.put(HypervisorType.KVM, VirtualNetworkApplianceManager.RouterTemplateKvm);
        hypervisorsMap.put(HypervisorType.VMware, VirtualNetworkApplianceManager.RouterTemplateVmware);
        hypervisorsMap.put(HypervisorType.Hyperv, VirtualNetworkApplianceManager.RouterTemplateHyperV);
        hypervisorsMap.put(HypervisorType.LXC, VirtualNetworkApplianceManager.RouterTemplateLxc);
    }

    @Override
    public boolean sendCommandsToRouter(final VirtualRouter router, final Commands cmds) throws AgentUnavailableException {
        if (!checkRouterVersion(router)) {
            s_logger.debug("Router requires upgrade. Unable to send command to router:" + router.getId() + ", router template version : " + router.getTemplateVersion()
                    + ", minimal required version : " + VirtualNetworkApplianceService.MinVRVersion);
            throw new CloudRuntimeException("Unable to send command. Upgrade in progress. Please contact administrator.");
        }
        Answer[] answers = null;
        try {
            answers = _agentMgr.send(router.getHostId(), cmds);
        } catch (final OperationTimedoutException e) {
            s_logger.warn("Timed Out", e);
            throw new AgentUnavailableException("Unable to send commands to virtual router ", router.getHostId(), e);
        }

        if (answers == null || answers.length != cmds.size()) {
            return false;
        }

        // FIXME: Have to return state for individual command in the future
        boolean result = true;
        for (final Answer answer : answers) {
            if (!answer.getResult()) {
                result = false;
                break;
            }
        }
        return result;
    }

    @Override
    public void handleSingleWorkingRedundantRouter(final List<? extends VirtualRouter> connectedRouters, final List<? extends VirtualRouter> disconnectedRouters,
            final String reason) throws ResourceUnavailableException {
        if (connectedRouters.isEmpty() || disconnectedRouters.isEmpty()) {
            return;
        }
        if (connectedRouters.size() != 1 || disconnectedRouters.size() != 1) {
            s_logger.warn("How many redundant routers do we have?? ");
            return;
        }
        if (!connectedRouters.get(0).getIsRedundantRouter()) {
            throw new ResourceUnavailableException("Who is calling this with non-redundant router or non-domain router?", DataCenter.class, connectedRouters.get(0)
                    .getDataCenterId());
        }
        if (!disconnectedRouters.get(0).getIsRedundantRouter()) {
            throw new ResourceUnavailableException("Who is calling this with non-redundant router or non-domain router?", DataCenter.class, disconnectedRouters.get(0)
                    .getDataCenterId());
        }

        final DomainRouterVO connectedRouter = (DomainRouterVO) connectedRouters.get(0);
        DomainRouterVO disconnectedRouter = (DomainRouterVO) disconnectedRouters.get(0);

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("About to stop the router " + disconnectedRouter.getInstanceName() + " due to: " + reason);
        }
        final String title = "Virtual router " + disconnectedRouter.getInstanceName() + " would be stopped after connecting back, due to " + reason;
        final String context = "Virtual router (name: " + disconnectedRouter.getInstanceName() + ", id: " + disconnectedRouter.getId()
                + ") would be stopped after connecting back, due to: " + reason;
        _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, disconnectedRouter.getDataCenterId(), disconnectedRouter.getPodIdToDeployIn(), title, context);
        disconnectedRouter.setStopPending(true);
        disconnectedRouter = _routerDao.persist(disconnectedRouter);

        final int connRouterPR = getRealPriority(connectedRouter);
        final int disconnRouterPR = getRealPriority(disconnectedRouter);
        if (connRouterPR < disconnRouterPR) {
            // connRouterPR < disconnRouterPR, they won't equal at any time
            if (!connectedRouter.getIsPriorityBumpUp()) {
                final BumpUpPriorityCommand command = new BumpUpPriorityCommand();
                command.setAccessDetail(NetworkElementCommand.ROUTER_IP, _routerControlHelper.getRouterControlIp(connectedRouter.getId()));
                command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, connectedRouter.getInstanceName());
                final Answer answer = _agentMgr.easySend(connectedRouter.getHostId(), command);
                if (!answer.getResult()) {
                    s_logger.error("Failed to bump up " + connectedRouter.getInstanceName() + "'s priority! " + answer.getDetails());
                }
            } else {
                final String t = "Can't bump up virtual router " + connectedRouter.getInstanceName() + "'s priority due to it's already bumped up!";
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, connectedRouter.getDataCenterId(), connectedRouter.getPodIdToDeployIn(), t, t);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.cloud.network.router.NetworkHelper#getRealPriority(com.cloud.vm.
     * DomainRouterVO)
     */
    @Override
    public int getRealPriority(final DomainRouterVO router) {
        int priority = router.getPriority();
        if (router.getIsPriorityBumpUp()) {
            priority += VirtualNetworkApplianceManager.DEFAULT_DELTA;
        }
        return priority;
    }

    // @Override
    /*
     * (non-Javadoc)
     * 
     * @see
     * com.cloud.network.router.NetworkHelper#getNicTO(com.cloud.network.router
     * .VirtualRouter, java.lang.Long, java.lang.String)
     */
    @Override
    public NicTO getNicTO(final VirtualRouter router, final Long networkId, final String broadcastUri) {
        NicProfile nicProfile = _networkModel.getNicProfile(router, networkId, broadcastUri);

        return _itMgr.toNicTO(nicProfile, router.getHypervisorType());
    }

    // @Override
    /*
     * (non-Javadoc)
     * 
     * @see com.cloud.network.router.NetworkHelper#destroyRouter(long,
     * com.cloud.user.Account, java.lang.Long)
     */
    @Override
    public VirtualRouter destroyRouter(final long routerId, final Account caller, final Long callerUserId) throws ResourceUnavailableException, ConcurrentOperationException {

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Attempting to destroy router " + routerId);
        }

        final DomainRouterVO router = _routerDao.findById(routerId);
        if (router == null) {
            return null;
        }

        _accountMgr.checkAccess(caller, null, true, router);

        _itMgr.expunge(router.getUuid());
        _routerDao.remove(router.getId());
        return router;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.cloud.network.router.NetworkHelper#checkRouterVersion(com.cloud.network
     * .router.VirtualRouter)
     */
    // @Override
    @Override
    public boolean checkRouterVersion(final VirtualRouter router) {
        if (!VirtualNetworkApplianceManagerImpl.routerVersionCheckEnabled.value()) {
            // Router version check is disabled.
            return true;
        }
        if (router.getTemplateVersion() == null) {
            return false;
        }
        final String trimmedVersion = Version.trimRouterVersion(router.getTemplateVersion());
        return Version.compare(trimmedVersion, VirtualNetworkApplianceService.MinVRVersion) >= 0;
    }

    protected DomainRouterVO start(DomainRouterVO router, final User user, final Account caller, final Map<Param, Object> params, final DeploymentPlan planToDeploy)
            throws StorageUnavailableException, InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        s_logger.debug("Starting router " + router);
        try {
            _itMgr.advanceStart(router.getUuid(), params, planToDeploy, null);
        } catch (final OperationTimedoutException e) {
            throw new ResourceUnavailableException("Starting router " + router + " failed! " + e.toString(), DataCenter.class, router.getDataCenterId());
        }
        if (router.isStopPending()) {
            s_logger.info("Clear the stop pending flag of router " + router.getHostName() + " after start router successfully!");
            router.setStopPending(false);
            router = _routerDao.persist(router);
        }
        // We don't want the failure of VPN Connection affect the status of
        // router, so we try to make connection
        // only after router start successfully
        final Long vpcId = router.getVpcId();
        if (vpcId != null) {
            _s2sVpnMgr.reconnectDisconnectedVpnByVpc(vpcId);
        }
        return _routerDao.findById(router.getId());
    }

    protected DomainRouterVO waitRouter(final DomainRouterVO router) {
        DomainRouterVO vm = _routerDao.findById(router.getId());

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Router " + router.getInstanceName() + " is not fully up yet, we will wait");
        }
        while (vm.getState() == State.Starting) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }

            // reload to get the latest state info
            vm = _routerDao.findById(router.getId());
        }

        if (vm.getState() == State.Running) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Router " + router.getInstanceName() + " is now fully up");
            }

            return router;
        }

        s_logger.warn("Router " + router.getInstanceName() + " failed to start. current state: " + vm.getState());
        return null;
    }

    // @Override
    /*
     * (non-Javadoc)
     * 
     * @see
     * com.cloud.network.router.NetworkHelper#startRouters(org.cloud.network
     * .router.deployment.RouterDeploymentDefinition)
     */
    @Override
    public List<DomainRouterVO> startRouters(final RouterDeploymentDefinition routerDeploymentDefinition) throws StorageUnavailableException, InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException {

        List<DomainRouterVO> runningRouters = new ArrayList<DomainRouterVO>();

        for (DomainRouterVO router : routerDeploymentDefinition.getRouters()) {
            boolean skip = false;
            final State state = router.getState();
            if (router.getHostId() != null && state != State.Running) {
                final HostVO host = _hostDao.findById(router.getHostId());
                if (host == null || host.getState() != Status.Up) {
                    skip = true;
                }
            }
            if (!skip) {
                if (state != State.Running) {
                    router = startVirtualRouter(router, _accountMgr.getSystemUser(), _accountMgr.getSystemAccount(), routerDeploymentDefinition.getParams());
                }
                if (router != null) {
                    runningRouters.add(router);
                }
            }
        }
        return runningRouters;
    }

    // @Override
    /*
     * (non-Javadoc)
     * 
     * @see
     * com.cloud.network.router.NetworkHelper#startVirtualRouter(com.cloud.vm
     * .DomainRouterVO, com.cloud.user.User, com.cloud.user.Account,
     * java.util.Map)
     */
    @Override
    public DomainRouterVO startVirtualRouter(final DomainRouterVO router, final User user, final Account caller, final Map<Param, Object> params)
            throws StorageUnavailableException, InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        if (router.getRole() != Role.VIRTUAL_ROUTER || !router.getIsRedundantRouter()) {
            return start(router, user, caller, params, null);
        }

        if (router.getState() == State.Running) {
            s_logger.debug("Redundant router " + router.getInstanceName() + " is already running!");
            return router;
        }

        //
        // If another thread has already requested a VR start, there is a
        // transition period for VR to transit from
        // Starting to Running, there exist a race conditioning window here
        // We will wait until VR is up or fail
        if (router.getState() == State.Starting) {
            return waitRouter(router);
        }

        DataCenterDeployment plan = new DataCenterDeployment(0, null, null, null, null, null);
        DomainRouterVO result = null;
        assert router.getIsRedundantRouter();
        final List<Long> networkIds = _routerDao.getRouterNetworks(router.getId());
        // Not support VPC now
        if (networkIds.size() > 1) {
            throw new ResourceUnavailableException("Unable to support more than one guest network for redundant router now!", DataCenter.class, router.getDataCenterId());
        }
        DomainRouterVO routerToBeAvoid = null;
        if (networkIds.size() != 0) {
            final List<DomainRouterVO> routerList = _routerDao.findByNetwork(networkIds.get(0));
            for (final DomainRouterVO rrouter : routerList) {
                if (rrouter.getHostId() != null && rrouter.getIsRedundantRouter() && rrouter.getState() == State.Running) {
                    if (routerToBeAvoid != null) {
                        throw new ResourceUnavailableException("Try to start router " + router.getInstanceName() + "(" + router.getId() + ")"
                                + ", but there are already two redundant routers with IP " + router.getPublicIpAddress() + ", they are " + rrouter.getInstanceName() + "("
                                + rrouter.getId() + ") and " + routerToBeAvoid.getInstanceName() + "(" + routerToBeAvoid.getId() + ")", DataCenter.class,
                                rrouter.getDataCenterId());
                    }
                    routerToBeAvoid = rrouter;
                }
            }
        }
        if (routerToBeAvoid == null) {
            return start(router, user, caller, params, null);
        }
        // We would try best to deploy the router to another place
        final int retryIndex = 5;
        final ExcludeList[] avoids = new ExcludeList[5];
        avoids[0] = new ExcludeList();
        avoids[0].addPod(routerToBeAvoid.getPodIdToDeployIn());
        avoids[1] = new ExcludeList();
        avoids[1].addCluster(_hostDao.findById(routerToBeAvoid.getHostId()).getClusterId());
        avoids[2] = new ExcludeList();
        final List<VolumeVO> volumes = _volumeDao.findByInstanceAndType(routerToBeAvoid.getId(), Volume.Type.ROOT);
        if (volumes != null && volumes.size() != 0) {
            avoids[2].addPool(volumes.get(0).getPoolId());
        }
        avoids[2].addHost(routerToBeAvoid.getHostId());
        avoids[3] = new ExcludeList();
        avoids[3].addHost(routerToBeAvoid.getHostId());
        avoids[4] = new ExcludeList();

        for (int i = 0; i < retryIndex; i++) {
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Try to deploy redundant virtual router:" + router.getHostName() + ", for " + i + " time");
            }
            plan.setAvoids(avoids[i]);
            try {
                result = start(router, user, caller, params, plan);
            } catch (final InsufficientServerCapacityException ex) {
                result = null;
            }
            if (result != null) {
                break;
            }
        }
        return result;
    }

    protected String retrieveTemplateName(HypervisorType hType, final long datacenterId) {
        if (hType == HypervisorType.BareMetal) {
            String peerHvType = _configDao.getValue(Config.BaremetalPeerHypervisorType.key());
            if (peerHvType == null) {
                throw new CloudRuntimeException(String.format("To use baremetal in advanced networking, you must set %s to type of hypervisor(e.g XenServer)"
                        + " that exists in the same zone with baremetal host. That hyperivsor is used to spring up virtual router for baremetal instance",
                        Config.BaremetalPeerHypervisorType.key()));
            }

            hType = HypervisorType.getType(peerHvType);
            if (HypervisorType.XenServer != hType && HypervisorType.KVM != hType && HypervisorType.VMware != hType) {
                throw new CloudRuntimeException(String.format("Baremetal only supports peer hypervisor(XenServer/KVM/VMWare) right now, you specified %s", peerHvType));
            }
        }

        return hypervisorsMap.get(hType).valueIn(datacenterId);
    }

    @Override
    public DomainRouterVO deployRouter(final RouterDeploymentDefinition routerDeploymentDefinition, final boolean startRouter) throws InsufficientAddressCapacityException,
            InsufficientServerCapacityException, InsufficientCapacityException, StorageUnavailableException, ResourceUnavailableException {

        final ServiceOfferingVO routerOffering = _serviceOfferingDao.findById(routerDeploymentDefinition.getOfferingId());
        final Account owner = routerDeploymentDefinition.getOwner();

        // Router is the network element, we don't know the hypervisor type yet.
        // Try to allocate the domR twice using diff hypervisors, and when
        // failed both times, throw the exception up
        final List<HypervisorType> hypervisors = getHypervisors(routerDeploymentDefinition);

        int allocateRetry = 0;
        int startRetry = 0;
        DomainRouterVO router = null;
        for (final Iterator<HypervisorType> iter = hypervisors.iterator(); iter.hasNext();) {
            final HypervisorType hType = iter.next();
            try {
                final long id = _routerDao.getNextInSequence(Long.class, "id");
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug(String.format("Allocating the VR with id=%s in datacenter %s with the hypervisor type %s", id, routerDeploymentDefinition.getDest()
                            .getDataCenter(), hType));
                }

                String templateName = retrieveTemplateName(hType, routerDeploymentDefinition.getDest().getDataCenter().getId());
                final VMTemplateVO template = _templateDao.findRoutingTemplate(hType, templateName);

                if (template == null) {
                    s_logger.debug(hType + " won't support system vm, skip it");
                    continue;
                }

                boolean offerHA = routerOffering.getOfferHA();
                /*
                 * We don't provide HA to redundant router VMs, admin should own
                 * it all, and redundant router themselves are HA
                 */
                if (routerDeploymentDefinition.isRedundant()) {
                    offerHA = false;
                }

                // routerDeploymentDefinition.getVpc().getId() ==> do not use
                // VPC because it is not a VPC offering.
                Long vpcId = routerDeploymentDefinition.getVpc() != null ? routerDeploymentDefinition.getVpc().getId() : null;

                router = new DomainRouterVO(id, routerOffering.getId(), routerDeploymentDefinition.getVirtualProvider().getId(), VirtualMachineName.getRouterName(id,
                        VirtualNetworkStatus.instance), template.getId(), template.getHypervisorType(), template.getGuestOSId(), owner.getDomainId(), owner.getId(),
                        routerDeploymentDefinition.isRedundant(), 0, false, RedundantState.UNKNOWN, offerHA, false, vpcId);

                router.setDynamicallyScalable(template.isDynamicallyScalable());
                router.setRole(Role.VIRTUAL_ROUTER);
                router = _routerDao.persist(router);
                LinkedHashMap<Network, List<? extends NicProfile>> networks = createRouterNetworks(routerDeploymentDefinition);
                _itMgr.allocate(router.getInstanceName(), template, routerOffering, networks, routerDeploymentDefinition.getPlan(), null);
                router = _routerDao.findById(router.getId());
            } catch (final InsufficientCapacityException ex) {
                if (allocateRetry < 2 && iter.hasNext()) {
                    s_logger.debug("Failed to allocate the VR with hypervisor type " + hType + ", retrying one more time");
                    continue;
                } else {
                    throw ex;
                }
            } finally {
                allocateRetry++;
            }

            if (startRouter) {
                try {
                    router = startVirtualRouter(router, _accountMgr.getSystemUser(), _accountMgr.getSystemAccount(), routerDeploymentDefinition.getParams());
                    break;
                } catch (final InsufficientCapacityException ex) {
                    if (startRetry < 2 && iter.hasNext()) {
                        s_logger.debug("Failed to start the VR  " + router + " with hypervisor type " + hType + ", " + "destroying it and recreating one more time");
                        // destroy the router
                        destroyRouter(router.getId(), _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM), User.UID_SYSTEM);
                        continue;
                    } else {
                        throw ex;
                    }
                } finally {
                    startRetry++;
                }
            } else {
                // return stopped router
                return router;
            }
        }

        return router;
    }

    protected void filterSupportedHypervisors(final List<HypervisorType> hypervisors) {
        // For non vpc we keep them all assuming all types in the list are
        // supported
    }

    protected String getNoHypervisorsErrMsgDetails() {
        return "";
    }

    protected List<HypervisorType> getHypervisors(final RouterDeploymentDefinition routerDeploymentDefinition) throws InsufficientServerCapacityException {
        final DeployDestination dest = routerDeploymentDefinition.getDest();
        List<HypervisorType> hypervisors = new ArrayList<HypervisorType>();

        if (dest.getCluster() != null) {
            if (dest.getCluster().getHypervisorType() == HypervisorType.Ovm) {
                hypervisors.add(getClusterToStartDomainRouterForOvm(dest.getCluster().getPodId()));
            } else {
                hypervisors.add(dest.getCluster().getHypervisorType());
            }
        } else {
            final HypervisorType defaults = _resourceMgr.getDefaultHypervisor(dest.getDataCenter().getId());
            if (defaults != HypervisorType.None) {
                hypervisors.add(defaults);
            } else {
                // if there is no default hypervisor, get it from the cluster
                hypervisors = _resourceMgr.getSupportedHypervisorTypes(dest.getDataCenter().getId(), true, routerDeploymentDefinition.getPlan().getPodId());
            }
        }

        filterSupportedHypervisors(hypervisors);

        if (hypervisors.isEmpty()) {
            if (routerDeploymentDefinition.getPodId() != null) {
                throw new InsufficientServerCapacityException("Unable to create virtual router, there are no clusters in the pod." + getNoHypervisorsErrMsgDetails(), Pod.class,
                        routerDeploymentDefinition.getPodId());
            }
            throw new InsufficientServerCapacityException("Unable to create virtual router, there are no clusters in the zone." + getNoHypervisorsErrMsgDetails(),
                    DataCenter.class, dest.getDataCenter().getId());
        }
        return hypervisors;
    }

    /*
     * Ovm won't support any system. So we have to choose a partner cluster in
     * the same pod to start domain router for us
     */
    protected HypervisorType getClusterToStartDomainRouterForOvm(final long podId) {
        final List<ClusterVO> clusters = _clusterDao.listByPodId(podId);
        for (final ClusterVO cv : clusters) {
            if (cv.getHypervisorType() == HypervisorType.Ovm || cv.getHypervisorType() == HypervisorType.BareMetal) {
                continue;
            }

            final List<HostVO> hosts = _resourceMgr.listAllHostsInCluster(cv.getId());
            if (hosts == null || hosts.isEmpty()) {
                continue;
            }

            for (final HostVO h : hosts) {
                if (h.getState() == Status.Up) {
                    s_logger.debug("Pick up host that has hypervisor type " + h.getHypervisorType() + " in cluster " + cv.getId() + " to start domain router for OVM");
                    return h.getHypervisorType();
                }
            }
        }

        final String errMsg = new StringBuilder("Cannot find an available cluster in Pod ").append(podId)
                .append(" to start domain router for Ovm. \n Ovm won't support any system vm including domain router, ")
                .append("please make sure you have a cluster with hypervisor type of any of xenserver/KVM/Vmware in the same pod")
                .append(" with Ovm cluster. And there is at least one host in UP status in that cluster.").toString();
        throw new CloudRuntimeException(errMsg);
    }

    public LinkedHashMap<Network, List<? extends NicProfile>> createRouterNetworks(final RouterDeploymentDefinition routerDeploymentDefinition)
            throws ConcurrentOperationException, InsufficientAddressCapacityException {

        // Form networks
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<Network, List<? extends NicProfile>>(3);
        // 1) Guest network
        boolean hasGuestNetwork = false;
        if (routerDeploymentDefinition.getGuestNetwork() != null) {
            s_logger.debug("Adding nic for Virtual Router in Guest network " + routerDeploymentDefinition.getGuestNetwork());
            String defaultNetworkStartIp = null, defaultNetworkStartIpv6 = null;
            if (!routerDeploymentDefinition.isPublicNetwork()) {
                final Nic placeholder = _networkModel.getPlaceholderNicForRouter(routerDeploymentDefinition.getGuestNetwork(), routerDeploymentDefinition.getPodId());
                if (routerDeploymentDefinition.getGuestNetwork().getCidr() != null) {
                    if (placeholder != null && placeholder.getIp4Address() != null) {
                        s_logger.debug("Requesting ipv4 address " + placeholder.getIp4Address() + " stored in placeholder nic for the network "
                                + routerDeploymentDefinition.getGuestNetwork());
                        defaultNetworkStartIp = placeholder.getIp4Address();
                    } else {
                        final String startIp = _networkModel.getStartIpAddress(routerDeploymentDefinition.getGuestNetwork().getId());
                        if (startIp != null && _ipAddressDao.findByIpAndSourceNetworkId(routerDeploymentDefinition.getGuestNetwork().getId(), startIp).getAllocatedTime() == null) {
                            defaultNetworkStartIp = startIp;
                        } else if (s_logger.isDebugEnabled()) {
                            s_logger.debug("First ipv4 " + startIp + " in network id=" + routerDeploymentDefinition.getGuestNetwork().getId()
                                    + " is already allocated, can't use it for domain router; will get random ip address from the range");
                        }
                    }
                }

                if (routerDeploymentDefinition.getGuestNetwork().getIp6Cidr() != null) {
                    if (placeholder != null && placeholder.getIp6Address() != null) {
                        s_logger.debug("Requesting ipv6 address " + placeholder.getIp6Address() + " stored in placeholder nic for the network "
                                + routerDeploymentDefinition.getGuestNetwork());
                        defaultNetworkStartIpv6 = placeholder.getIp6Address();
                    } else {
                        final String startIpv6 = _networkModel.getStartIpv6Address(routerDeploymentDefinition.getGuestNetwork().getId());
                        if (startIpv6 != null && _ipv6Dao.findByNetworkIdAndIp(routerDeploymentDefinition.getGuestNetwork().getId(), startIpv6) == null) {
                            defaultNetworkStartIpv6 = startIpv6;
                        } else if (s_logger.isDebugEnabled()) {
                            s_logger.debug("First ipv6 " + startIpv6 + " in network id=" + routerDeploymentDefinition.getGuestNetwork().getId()
                                    + " is already allocated, can't use it for domain router; will get random ipv6 address from the range");
                        }
                    }
                }
            }

            final NicProfile gatewayNic = new NicProfile(defaultNetworkStartIp, defaultNetworkStartIpv6);
            if (routerDeploymentDefinition.isPublicNetwork()) {
                if (routerDeploymentDefinition.isRedundant()) {
                    gatewayNic.setIp4Address(_ipAddrMgr.acquireGuestIpAddress(routerDeploymentDefinition.getGuestNetwork(), null));
                } else {
                    gatewayNic.setIp4Address(routerDeploymentDefinition.getGuestNetwork().getGateway());
                }
                gatewayNic.setBroadcastUri(routerDeploymentDefinition.getGuestNetwork().getBroadcastUri());
                gatewayNic.setBroadcastType(routerDeploymentDefinition.getGuestNetwork().getBroadcastDomainType());
                gatewayNic.setIsolationUri(routerDeploymentDefinition.getGuestNetwork().getBroadcastUri());
                gatewayNic.setMode(routerDeploymentDefinition.getGuestNetwork().getMode());
                final String gatewayCidr = routerDeploymentDefinition.getGuestNetwork().getCidr();
                gatewayNic.setNetmask(NetUtils.getCidrNetmask(gatewayCidr));
            } else {
                gatewayNic.setDefaultNic(true);
            }

            networks.put(routerDeploymentDefinition.getGuestNetwork(), new ArrayList<NicProfile>(Arrays.asList(gatewayNic)));
            hasGuestNetwork = true;
        }

        // 2) Control network
        s_logger.debug("Adding nic for Virtual Router in Control network ");
        List<? extends NetworkOffering> offerings = _networkModel.getSystemAccountNetworkOfferings(NetworkOffering.SystemControlNetwork);
        NetworkOffering controlOffering = offerings.get(0);
        Network controlConfig = _networkMgr.setupNetwork(VirtualNetworkStatus.account, controlOffering, routerDeploymentDefinition.getPlan(), null, null, false).get(0);
        networks.put(controlConfig, new ArrayList<NicProfile>());
        // 3) Public network
        if (routerDeploymentDefinition.isPublicNetwork()) {
            s_logger.debug("Adding nic for Virtual Router in Public network ");
            // if source nat service is supported by the network, get the source
            // nat ip address
            final NicProfile defaultNic = new NicProfile();
            defaultNic.setDefaultNic(true);
            final PublicIp sourceNatIp = routerDeploymentDefinition.getSourceNatIP();
            defaultNic.setIp4Address(sourceNatIp.getAddress().addr());
            defaultNic.setGateway(sourceNatIp.getGateway());
            defaultNic.setNetmask(sourceNatIp.getNetmask());
            defaultNic.setMacAddress(sourceNatIp.getMacAddress());
            // get broadcast from public network
            final Network pubNet = _networkDao.findById(sourceNatIp.getNetworkId());
            if (pubNet.getBroadcastDomainType() == BroadcastDomainType.Vxlan) {
                defaultNic.setBroadcastType(BroadcastDomainType.Vxlan);
                defaultNic.setBroadcastUri(BroadcastDomainType.Vxlan.toUri(sourceNatIp.getVlanTag()));
                defaultNic.setIsolationUri(BroadcastDomainType.Vxlan.toUri(sourceNatIp.getVlanTag()));
            } else {
                defaultNic.setBroadcastType(BroadcastDomainType.Vlan);
                defaultNic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(sourceNatIp.getVlanTag()));
                defaultNic.setIsolationUri(IsolationType.Vlan.toUri(sourceNatIp.getVlanTag()));
            }
            if (hasGuestNetwork) {
                defaultNic.setDeviceId(2);
            }
            final NetworkOffering publicOffering = _networkModel.getSystemAccountNetworkOfferings(NetworkOffering.SystemPublicNetwork).get(0);
            final List<? extends Network> publicNetworks = _networkMgr.setupNetwork(VirtualNetworkStatus.account, publicOffering, routerDeploymentDefinition.getPlan(), null,
                    null, false);
            final String publicIp = defaultNic.getIp4Address();
            // We want to use the identical MAC address for RvR on public
            // interface if possible
            final NicVO peerNic = _nicDao.findByIp4AddressAndNetworkId(publicIp, publicNetworks.get(0).getId());
            if (peerNic != null) {
                s_logger.info("Use same MAC as previous RvR, the MAC is " + peerNic.getMacAddress());
                defaultNic.setMacAddress(peerNic.getMacAddress());
            }
            networks.put(publicNetworks.get(0), new ArrayList<NicProfile>(Arrays.asList(defaultNic)));
        }

        return networks;
    }
}
