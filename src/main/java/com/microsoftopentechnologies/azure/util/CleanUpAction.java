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

package com.microsoftopentechnologies.azure.util;

/**
 * Represents the action that should be taken by the Azure Slave CleanUp Task
 * if the machine is to be cleaned up if it is in an offline state.
 * @author mmitche
 */
public enum CleanUpAction {
    // Machine should be kept and not cleaned up even if in an offline state.
    // In this state during creation or if machine was previously shut down.
    BLOCK,
    // Machine should be deleted if in an offline state
    DELETE,
    // Machine should be shut down if in an offline state
    SHUTDOWN,
    // Machine should perform the default action for the node (shutdown or delete)
    DEFAULT
}
