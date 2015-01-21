package com.microsoftopentechnologies.azure.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.NoRetryStrategy;
import com.microsoftopentechnologies.azure.retry.RetryStrategy;
import com.microsoftopentechnologies.azure.retry.RetryTask;

public class ExecutionEngine {
	
	public static <T> T executeWithNoRetry(Callable<T> task) throws AzureCloudException {
		return executeWithRetry(task, new NoRetryStrategy());
	}
	
	public static <T> T executeWithRetry(Callable<T> task, RetryStrategy retryStrategy) throws AzureCloudException {
		RetryTask<T> retryTask = new RetryTask<T>(task, retryStrategy);
		
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		Future<T> result = executorService.submit(retryTask);
		
		try {
			if (retryStrategy.getMaxTimeoutInSeconds() == 0) {
				return result.get();
			} else {
				return result.get(retryStrategy.getMaxTimeoutInSeconds(), TimeUnit.SECONDS);
			}	
		} catch (TimeoutException timeoutException) {
			throw new AzureCloudException("Operation timed out: ",timeoutException);
		} catch (Exception ex) {
			throw new AzureCloudException(ex.getMessage(), ex);
		} finally {
			executorService.shutdown();
		}
	}
	
}
