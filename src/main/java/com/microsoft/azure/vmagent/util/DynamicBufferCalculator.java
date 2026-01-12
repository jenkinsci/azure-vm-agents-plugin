/*
 Copyright 2016 Microsoft, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.vmagent.util;

import com.microsoft.azure.vmagent.AzureVMAgent;
import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMComputer;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Queue;
import jenkins.model.Jenkins;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for calculating dynamic buffer sizes based on current workload.
 * This helps ensure there are always idle machines ready to pick up new jobs immediately.
 */
public final class DynamicBufferCalculator {

    private static final Logger LOGGER = Logger.getLogger(DynamicBufferCalculator.class.getName());

    private DynamicBufferCalculator() {
        // Utility class, prevent instantiation
    }

    /**
     * Counts the number of busy machines for a specific template.
     *
     * @param template The template to count busy machines for
     * @return Number of busy machines
     */
    public static int countBusyMachines(AzureVMAgentTemplate template) {
        int busyCount = 0;
        for (Computer computer : Jenkins.get().getComputers()) {
            if (computer instanceof AzureVMComputer) {
                AzureVMComputer azureComputer = (AzureVMComputer) computer;
                AzureVMAgent agent = azureComputer.getNode();
                if (agent != null
                        && TemplateUtil.checkSame(agent.getTemplate(), template)
                        && !computer.isIdle()) {
                    busyCount++;
                }
            }
        }
        LOGGER.log(Level.FINE, "Template {0} has {1} busy machines",
                new Object[]{template.getTemplateName(), busyCount});
        return busyCount;
    }

    /**
     * Counts the number of idle machines for a specific template.
     *
     * @param template The template to count idle machines for
     * @return Number of idle machines
     */
    public static int countIdleMachines(AzureVMAgentTemplate template) {
        int idleCount = 0;
        for (Computer computer : Jenkins.get().getComputers()) {
            if (computer instanceof AzureVMComputer) {
                AzureVMComputer azureComputer = (AzureVMComputer) computer;
                AzureVMAgent agent = azureComputer.getNode();
                if (agent != null
                        && TemplateUtil.checkSame(agent.getTemplate(), template)
                        && computer.isIdle()
                        && computer.isOnline()) {
                    idleCount++;
                }
            }
        }
        LOGGER.log(Level.FINE, "Template {0} has {1} idle machines",
                new Object[]{template.getTemplateName(), idleCount});
        return idleCount;
    }

    /**
     * Counts the total number of machines (both busy and idle) for a specific template.
     *
     * @param template The template to count machines for
     * @return Total number of machines
     */
    public static int countTotalMachines(AzureVMAgentTemplate template) {
        int totalCount = 0;
        for (Computer computer : Jenkins.get().getComputers()) {
            if (computer instanceof AzureVMComputer) {
                AzureVMComputer azureComputer = (AzureVMComputer) computer;
                AzureVMAgent agent = azureComputer.getNode();
                if (agent != null && TemplateUtil.checkSame(agent.getTemplate(), template)) {
                    totalCount++;
                }
            }
        }
        LOGGER.log(Level.FINE, "Template {0} has {1} total machines",
                new Object[]{template.getTemplateName(), totalCount});
        return totalCount;
    }

    /**
     * Counts the number of queued items that could be handled by this template.
     * Only counts buildable items that match the template's labels.
     *
     * @param template The template to check queue for
     * @return Number of queued items matching this template
     */
    public static int countQueuedItemsForTemplate(AzureVMAgentTemplate template) {
        int queueCount = 0;
        Queue queue = Jenkins.get().getQueue();
        Queue.Item[] items = queue.getItems();

        String templateLabels = template.getLabels();
        boolean templateHasLabels = templateLabels != null && !templateLabels.trim().isEmpty();

        for (Queue.Item item : items) {
            if (item instanceof Queue.BuildableItem) {
                Label itemLabel = item.getAssignedLabel();
                // Item matches if:
                // 1. Item has no label requirement (can run anywhere)
                // 2. Template has no label (accepts all)
                // 3. Labels contain a match (simplified check via string)
                boolean matches = itemLabel == null || !templateHasLabels;
                if (!matches && itemLabel != null) {
                    // Check if the item's label is contained in the template's labels
                    String itemLabelName = itemLabel.getName();
                    matches = templateLabels.contains(itemLabelName);
                }
                if (matches) {
                    queueCount++;
                }
            }
        }
        LOGGER.log(Level.FINE, "Template {0} has {1} queued items",
                new Object[]{template.getTemplateName(), queueCount});
        return queueCount;
    }

    /**
     * Calculates the number of additional machines needed to satisfy the dynamic buffer requirement.
     *
     * @param template             The template to calculate for (can be null for logging purposes)
     * @param effectivePoolSize    The desired effective pool size (including buffer)
     * @param currentTotalMachines Current total number of machines for this template
     * @return Number of additional machines needed (0 if buffer is satisfied)
     */
    public static int calculateMachinesToProvision(AzureVMAgentTemplate template,
                                                    int effectivePoolSize,
                                                    int currentTotalMachines) {
        int deficit = effectivePoolSize - currentTotalMachines;
        int toProvision = Math.max(0, deficit);

        String templateName = template != null ? template.getTemplateName() : "unknown";
        LOGGER.log(Level.FINE,
                "Template {0}: effectivePoolSize={1}, currentTotal={2}, toProvision={3}",
                new Object[]{templateName, effectivePoolSize, currentTotalMachines, toProvision});

        return toProvision;
    }

    /**
     * Comprehensive calculation that returns all buffer-related metrics for a template.
     *
     * @param template The template to analyze
     * @return BufferMetrics containing all relevant counts
     */
    public static BufferMetrics calculateBufferMetrics(AzureVMAgentTemplate template) {
        int busy = countBusyMachines(template);
        int idle = countIdleMachines(template);
        int total = countTotalMachines(template);
        int queued = countQueuedItemsForTemplate(template);

        return new BufferMetrics(busy, idle, total, queued);
    }

    /**
     * Container class for buffer-related metrics.
     */
    public static class BufferMetrics {
        private final int busyMachines;
        private final int idleMachines;
        private final int totalMachines;
        private final int queuedItems;

        public BufferMetrics(int busyMachines, int idleMachines, int totalMachines, int queuedItems) {
            this.busyMachines = busyMachines;
            this.idleMachines = idleMachines;
            this.totalMachines = totalMachines;
            this.queuedItems = queuedItems;
        }

        public int getBusyMachines() {
            return busyMachines;
        }

        public int getIdleMachines() {
            return idleMachines;
        }

        public int getTotalMachines() {
            return totalMachines;
        }

        public int getQueuedItems() {
            return queuedItems;
        }

        @Override
        public String toString() {
            return String.format("BufferMetrics{busy=%d, idle=%d, total=%d, queued=%d}",
                    busyMachines, idleMachines, totalMachines, queuedItems);
        }
    }
}
