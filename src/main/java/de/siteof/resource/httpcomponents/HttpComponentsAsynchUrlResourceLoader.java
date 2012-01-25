package de.siteof.resource.httpcomponents;

import java.io.IOException;

import de.siteof.resource.AbstractUrlResourceLoader;
import de.siteof.resource.ICookieManager;
import de.siteof.resource.IResource;
import de.siteof.resource.IResourceLoader;
import de.siteof.task.ITaskManager;

public class HttpComponentsAsynchUrlResourceLoader extends AbstractUrlResourceLoader {

	private final ICookieManager cookieManager;

	public HttpComponentsAsynchUrlResourceLoader(IResourceLoader parent, ICookieManager cookieManager,
			ITaskManager taskManager) {
		super(parent, taskManager);
		this.cookieManager = cookieManager;
	}

	protected IResource getUrlResource(String name) throws IOException {
		return new HttpComponentsAsynchUrlResource(name, this.getCookieManager(), this.getTaskManager());
	}

	public ICookieManager getCookieManager() {
		return cookieManager;
	}

}
