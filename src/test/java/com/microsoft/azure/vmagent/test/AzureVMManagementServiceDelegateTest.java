package com.microsoft.azure.vmagent.test;

import com.microsoft.azure.CloudException;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.compute.VirtualMachines;
import com.microsoft.azure.vmagent.AzureVMAgent;
import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import org.junit.Before;
import org.junit.Test;
import rx.Completable;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AzureVMManagementServiceDelegateTest {
    private AzureVMManagementServiceDelegate delegate;
    private Azure azureClient;

    @Before
    public void init() {
        azureClient = mock(Azure.class);
        delegate = spy(AzureVMManagementServiceDelegate.getInstance(azureClient, null));
    }

    @Test
    public void testStartVirtualMachine_startRestartFailOnce() throws AzureCloudException {
        VirtualMachines virtualMachines = mock(VirtualMachines.class);
        VirtualMachine virtualMachine = mock(VirtualMachine.class);
        when(azureClient.virtualMachines()).thenReturn(virtualMachines);

        when(virtualMachines.getByResourceGroup(anyString(), anyString())).thenReturn(virtualMachine);
        Completable startCompletable = mock(Completable.class);
        when(startCompletable.await(anyLong(), any(TimeUnit.class))).thenReturn(false).thenReturn(true);
        Completable restartCompletable = mock(Completable.class);
        when(restartCompletable.await(anyLong(), any(TimeUnit.class))).thenReturn(false).thenReturn(true);

        when(virtualMachine.startAsync()).thenReturn(startCompletable);
        when(virtualMachine.restartAsync()).thenReturn(restartCompletable);

        AzureVMAgent vmAgent = mock(AzureVMAgent.class);
        when(vmAgent.getResourceGroupName()).thenReturn("resourceGroup");
        when(vmAgent.getNodeName()).thenReturn("nodeName");
        delegate.startVirtualMachine(vmAgent);

        verify(virtualMachine, times(1)).startAsync();
        verify(virtualMachine, times(2)).restartAsync();
    }

    @Test
    public void testStartVirtualMachine_getResourceFail() throws AzureCloudException {
        VirtualMachines virtualMachines = mock(VirtualMachines.class);
        VirtualMachine virtualMachine = mock(VirtualMachine.class);
        when(azureClient.virtualMachines()).thenReturn(virtualMachines);

        when(virtualMachines.getByResourceGroup(anyString(), anyString())).thenThrow(new CloudException("cloudException", null)).thenReturn(virtualMachine);
        Completable startCompletable = mock(Completable.class);
        when(startCompletable.await(anyLong(), any(TimeUnit.class))).thenReturn(true);
        Completable restartCompletable = mock(Completable.class);
        when(restartCompletable.await(anyLong(), any(TimeUnit.class))).thenReturn(true);

        when(virtualMachine.startAsync()).thenReturn(startCompletable);
        when(virtualMachine.restartAsync()).thenReturn(restartCompletable);

        AzureVMAgent vmAgent = mock(AzureVMAgent.class);
        when(vmAgent.getResourceGroupName()).thenReturn("resourceGroup");
        when(vmAgent.getNodeName()).thenReturn("nodeName");
        delegate.startVirtualMachine(vmAgent);

        verify(virtualMachine, times(1)).startAsync();
        verify(virtualMachine, never()).restartAsync();
    }

    @Test(expected = AzureCloudException.class)
    public void testStartVirtualMachine_startRestartFail() throws Exception {
        VirtualMachines virtualMachines = mock(VirtualMachines.class);
        VirtualMachine virtualMachine = mock(VirtualMachine.class);
        when(azureClient.virtualMachines()).thenReturn(virtualMachines);

        when(virtualMachines.getByResourceGroup(anyString(), anyString())).thenReturn(virtualMachine);
        Completable startCompletable = mock(Completable.class);
        when(startCompletable.await(anyLong(), any(TimeUnit.class))).thenReturn(false);
        Completable restartCompletable = mock(Completable.class);
        when(restartCompletable.await(anyLong(), any(TimeUnit.class))).thenReturn(false);

        when(virtualMachine.startAsync()).thenReturn(startCompletable);
        when(virtualMachine.restartAsync()).thenReturn(restartCompletable);

        AzureVMAgent vmAgent = mock(AzureVMAgent.class);
        when(vmAgent.getResourceGroupName()).thenReturn("resourceGroup");
        when(vmAgent.getNodeName()).thenReturn("nodeName");

        // Replace retry count to save the test time
        when(delegate.getStartVirtualMachineRetryCount()).thenReturn(2);

        delegate.startVirtualMachine(vmAgent);

        verify(virtualMachine, times(1)).startAsync();
        verify(virtualMachine, times(3)).restartAsync();
    }
}
