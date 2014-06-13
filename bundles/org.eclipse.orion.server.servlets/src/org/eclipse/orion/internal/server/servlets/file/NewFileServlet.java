/*******************************************************************************
 * Copyright (c) 2010, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.servlets.file;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.orion.internal.server.servlets.Activator;
import org.eclipse.orion.internal.server.servlets.ServletResourceHandler;
import org.eclipse.orion.server.core.LogHelper;
import org.eclipse.orion.server.core.OrionConfiguration;
import org.eclipse.orion.server.core.ServerConstants;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.orion.server.core.metastore.ProjectInfo;
import org.eclipse.orion.server.core.metastore.WorkspaceInfo;
import org.eclipse.orion.server.core.resources.Base64;
import org.eclipse.orion.server.servlets.OrionServlet;
import org.eclipse.osgi.util.NLS;

/**
 * Servlet to handle file system access.
 */
public class NewFileServlet extends OrionServlet {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private ServletResourceHandler<IFileStore> fileSerializer;

	public NewFileServlet() {
		super();
	}

	@Override
	public void init() throws ServletException {
		super.init();
		fileSerializer = new ServletFileStoreHandler(getStatusHandler(), getServletContext());
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		traceRequest(req);
		String pathInfo = req.getPathInfo();
		IPath path = pathInfo == null ? Path.ROOT : new Path(pathInfo);

		// prevent path canonicalization hacks
		if (pathInfo != null && !pathInfo.equals(path.toString())) {
			handleException(resp, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_FORBIDDEN, NLS.bind("Forbidden: {0}", pathInfo), null));
			return;
		}
		//don't allow anyone to mess with metadata
		if (path.segmentCount() > 0 && ".metadata".equals(path.segment(0))) { //$NON-NLS-1$
			handleException(resp, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_FORBIDDEN, NLS.bind("Forbidden: {0}", pathInfo), null));
			return;
		}
		IFileStore file = getFileStore(req, path);
		IFileStore testLink = file;
		while (testLink != null) {
			IFileInfo info = testLink.fetchInfo();
			if (info.getAttribute(EFS.ATTRIBUTE_SYMLINK)) {
				if (file == testLink) {
					handleException(resp, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_FORBIDDEN, NLS.bind("Forbidden: {0}", pathInfo), null));
				} else {
					handleException(resp, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_NOT_FOUND, NLS.bind("File not found: {0}", pathInfo), null));
				}
				return;
			}
			testLink = testLink.getParent();
		}

		if (file == null) {
			handleException(resp, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_NOT_FOUND, NLS.bind("File not found: {0}", pathInfo), null));
			return;
		}
		if (fileSerializer.handleRequest(req, resp, file))
			return;
		// finally invoke super to return an error for requests we don't know how to handle
		super.doGet(req, resp);
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doGet(req, resp);
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doGet(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doGet(req, resp);
	}

	/**
	 * Returns the store representing the file to be retrieved for the given
	 * request or <code>null</code> if an error occurred.
	 * @param request The current servlet request, or <code>null</code> if unknown
	 * @param path The path of the file resource to obtain the store for
	 */
	public static IFileStore getFileStore(HttpServletRequest request, IPath path) {
		try {
			if (path.segmentCount() == 0) {
				return null;
			} else if (path.segmentCount() == 1) {
				if (!OrionConfiguration.getMetaStorePreference().equals(ServerConstants.CONFIG_META_STORE_LEGACY)) {
					// Bug 415700: handle path format /workspaceId, only supported by SimpleMetaStore 
					WorkspaceInfo workspace = OrionConfiguration.getMetaStore().readWorkspace(path.segment(0));
					if (workspace != null) {
						return getFileStore(request, workspace);
					}
				}
				return null;
			}
			//path format is /workspaceId/projectName/[suffix]
			ProjectInfo project = OrionConfiguration.getMetaStore().readProject(path.segment(0), path.segment(1));
			if (project != null) {
				return getFileStore(request, project).getFileStore(path.removeFirstSegments(2));
			}
			// Bug 415700: handle path format /workspaceId/[file] only supported by SimpleMetaStore 
			if (path.segmentCount() == 2 && !OrionConfiguration.getMetaStorePreference().equals(ServerConstants.CONFIG_META_STORE_LEGACY)) {
				WorkspaceInfo workspace = OrionConfiguration.getMetaStore().readWorkspace(path.segment(0));
				if (workspace != null) {
					return getFileStore(request, workspace).getChild(path.segment(1));
				}
			}
			return null;
		} catch (CoreException e) {
			LogHelper.log(new Status(IStatus.WARNING, Activator.PI_SERVER_SERVLETS, 1, NLS.bind("An error occurred when getting file store for path {0}", path), e));
			// fallback and return null
		}
		return null;
	}

	/**
	 * Returns the store representing the file to be retrieved for the given
	 * request or <code>null</code> if an error occurred.
	 * @param request The current servlet request.
	 * @param project The workspace to obtain the store for.
	 */
	public static IFileStore getFileStore(HttpServletRequest request, WorkspaceInfo workspace) {
		try {
			if (workspace.getUniqueId() == null) {
				return null;
			}
			IFileStore fileStore = OrionConfiguration.getMetaStore().getWorkspaceContentLocation(workspace.getUniqueId());
			return wrap(fileStore);
		} catch (CoreException e) {
			LogHelper.log(new Status(IStatus.WARNING, Activator.PI_SERVER_SERVLETS, 1, "An error occurred when getting workspace store for path", e));
			// fallback and return null
		}
		return null;
	}

	/**
	 * Returns the store representing the file to be retrieved for the given
	 * request or <code>null</code> if an error occurred.
	 * @param request The current servlet request, or <code>null</code> if unknown
	 * @param project The project to obtain the store for.
	 */
	public static IFileStore getFileStore(HttpServletRequest request, ProjectInfo project) throws CoreException {
		URI location = project.getContentLocation();
		if (location.isAbsolute()) {
			//insert authentication details from request if available
			if (request != null && !EFS.SCHEME_FILE.equals(location.getScheme()) && location.getUserInfo() == null) {
				String authHead = request.getHeader("Authorization"); //$NON-NLS-1$
				if (authHead != null && authHead.toUpperCase(Locale.ENGLISH).startsWith("BASIC")) { //$NON-NLS-1$
					String base64 = authHead.substring(6);
					String authString = new String(Base64.decode(base64.getBytes()));
					if (authString.length() > 0) {
						try {
							location = new URI(location.getScheme(), authString, location.getHost(), location.getPort(), location.getPath(), location.getQuery(), location.getFragment());
						} catch (URISyntaxException e) {
							//just fall through and use original location
						}
					}
				}
			}
			return wrap(EFS.getStore(location));
		}
		//there is no scheme but it could still be an absolute path
		IPath localPath = new Path(location.getPath());
		if (localPath.isAbsolute()) {
			return wrap(EFS.getLocalFileSystem().getStore(localPath));
		}
		//treat relative location as relative to the file system root
		IFileStore root = OrionConfiguration.getMetaStore().getUserHome(request.getRemoteUser());
		return wrap(root.getChild(location.toString()));
	}

	private static IFileStore wrap(IFileStore store) {
		if (FilesystemModificationListenerManager.getInstance().hasListeners()) {
			return FileStoreNotificationWrapper.wrap(store);
		}

		return store;
	}
}
