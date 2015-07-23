/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
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
package com.microsoftopentechnologies.azure.exceptions;

public class UnrecoverableCloudException extends AzureCloudException {

    public UnrecoverableCloudException(final String message) {
        super(message);
    }

    public UnrecoverableCloudException() {
        super();
    }

    public UnrecoverableCloudException(final String msg, final Exception excep) {
        super(msg, excep);
    }

    public UnrecoverableCloudException(final Exception excep) {
        super(excep);
    }

    private static final long serialVersionUID = -8157417759485046943L;

}
