package de.siteof.resource.httpcomponents.test;

import org.junit.Test;

import de.siteof.httpclient.test.AbstractResourceDownloaderTest;
import de.siteof.httpclient.test.TestDownloadParameters;
import de.siteof.resource.CookieManager;
import de.siteof.resource.ICookieManager;
import de.siteof.resource.IResourceLoader;
import de.siteof.resource.httpcomponents.HttpComponentsAsynchUrlResourceLoader;
import de.siteof.task.ITaskManager;
import de.siteof.task.ThreadPoolTaskManager;

public class HttpComponentsDownloadTest extends AbstractResourceDownloaderTest {

	@Test
	public void testResourceDownloaderHttpComponents() throws Exception {
		TestDownloadParameters parameters = getTestParameters();
		parameters.filename = null;
		setServletResponse(parameters);

		ITaskManager taskManager = new ThreadPoolTaskManager(50);
		taskManager.start();

		ICookieManager cookieManager = new CookieManager();
		IResourceLoader resourceLoader = new HttpComponentsAsynchUrlResourceLoader(null, cookieManager, taskManager);

		doTestResourceDownloader(resourceLoader, parameters);
	}

}
