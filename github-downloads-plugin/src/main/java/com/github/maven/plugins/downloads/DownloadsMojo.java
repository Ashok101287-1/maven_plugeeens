/*
 * Copyright (c) 2011 GitHub Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */
package com.github.maven.plugins.downloads;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.eclipse.egit.github.core.Download;
import org.eclipse.egit.github.core.DownloadResource;
import org.eclipse.egit.github.core.RepositoryId;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.IGitHubConstants;
import org.eclipse.egit.github.core.client.RequestException;
import org.eclipse.egit.github.core.service.DownloadService;

/**
 * Mojo that uploads a built resource as a GitHub repository download
 * 
 * @author Kevin Sawicki (kevin@github.com)
 * @goal upload
 */
public class DownloadsMojo extends AbstractMojo {

	/**
	 * Are any given values null or empty?
	 * 
	 * @param values
	 * @return true if any null or empty, false otherwise
	 */
	public static boolean isEmpty(final String... values) {
		if (values == null || values.length == 0)
			return true;
		for (String value : values)
			if (value == null || value.length() == 0)
				return true;
		return false;
	}

	/**
	 * Extra repository id from given SCM URL
	 * 
	 * @param url
	 * @return repository id or null if extraction fails
	 */
	public static RepositoryId extractRepositoryFromScmUrl(String url) {
		if (isEmpty(url))
			return null;
		int ghIndex = url.indexOf(IGitHubConstants.HOST_DEFAULT);
		if (ghIndex == -1 || ghIndex + 1 >= url.length())
			return null;
		if (!url.endsWith(IGitHubConstants.SUFFIX_GIT))
			return null;
		url = url.substring(ghIndex + IGitHubConstants.HOST_DEFAULT.length()
				+ 1, url.length() - IGitHubConstants.SUFFIX_GIT.length());
		return RepositoryId.createFromId(url);
	}

	/**
	 * Owner of repository to upload to
	 * 
	 * @parameter expression="${github.downloads.repositoryOwner}"
	 */
	private String repositoryOwner;

	/**
	 * Name of repository to upload to
	 * 
	 * @parameter expression="${github.downloads.repositoryName}"
	 */
	private String repositoryName;

	/**
	 * User name for authentication
	 * 
	 * @parameter expression="${github.downloads.userName}"
	 */
	private String userName;

	/**
	 * User name for authentication
	 * 
	 * @parameter expression="${github.downloads.password}"
	 */
	private String password;

	/**
	 * Description of download
	 * 
	 * @parameter
	 */
	private String description;

	/**
	 * User name for authentication
	 * 
	 * @parameter expression="${github.downloads.oauth2Token}"
	 */
	private String oauth2Token;

	/**
	 * Override existing downloads
	 * 
	 * @parameter expression="${github.downloads.override}"
	 */
	private boolean override;

	/**
	 * Host for API calls
	 * 
	 * @parameter expression="${github.downloads.host}"
	 */
	private String host;

	/**
	 * Project being built
	 * 
	 * @parameter expression="${project}
	 * @required
	 */
	private MavenProject project;

	/**
	 * Files to exclude
	 * 
	 * @parameter
	 */
	private String[] excludes;

	/**
	 * Files to include
	 * 
	 * @parameter
	 */
	private String[] includes;

	/**
	 * Get repository
	 * 
	 * @return repository id or null if none configured
	 */
	protected RepositoryId getRepository() {
		RepositoryId repo = null;
		if (!isEmpty(repositoryOwner, repositoryName))
			repo = RepositoryId.create(repositoryOwner, repositoryName);
		if (repo == null && !isEmpty(project.getUrl()))
			repo = RepositoryId.createFromUrl(project.getUrl());
		if (repo == null && !isEmpty(project.getScm().getUrl()))
			repo = RepositoryId.createFromUrl(project.getScm().getUrl());
		if (repo == null)
			repo = extractRepositoryFromScmUrl(project.getScm().getConnection());
		if (repo == null)
			repo = extractRepositoryFromScmUrl(project.getScm()
					.getDeveloperConnection());
		return repo;
	}

	/**
	 * Create client
	 * 
	 * @return client
	 * @throws MojoExecutionException
	 */
	protected GitHubClient createClient() throws MojoExecutionException {
		final Log log = getLog();
		GitHubClient client;
		if (!isEmpty(host))
			client = new GitHubClient(host, -1, IGitHubConstants.PROTOCOL_HTTPS);
		else
			client = new GitHubClient();
		if (userName != null && password != null) {
			if (log.isDebugEnabled())
				log.debug("Using basic authentication with username: "
						+ userName);
			client.setCredentials(userName, password);
		} else if (oauth2Token != null) {
			if (log.isDebugEnabled())
				log.debug("Using OAuth2 authentication");
			client.setOAuth2Token(oauth2Token);
		} else
			throw new MojoExecutionException(
					"No authentication credentials configured");
		return client;
	}

	/**
	 * Get exception message for {@link IOException}
	 * 
	 * @param e
	 * @return message
	 */
	protected String getExceptionMessage(IOException e) {
		String message = null;
		if (e instanceof RequestException) {
			RequestException requestException = (RequestException) e;
			message = Integer.toString(requestException.getStatus()) + " "
					+ requestException.formatErrors();
		} else
			message = e.getMessage();
		return message;
	}

	/**
	 * Get files to create downloads
	 * 
	 * @return non-null but possibly empty list of files
	 */
	protected List<File> getFiles() {
		List<File> files;
		boolean hasIncludes = !isEmpty(includes);
		boolean hasExcludes = !isEmpty(excludes);
		if (hasIncludes || hasExcludes) {
			files = new ArrayList<File>();
			DirectoryScanner scanner = new DirectoryScanner();
			String baseDir = project.getBuild().getDirectory();
			scanner.setBasedir(baseDir);
			if (hasIncludes)
				scanner.setIncludes(includes);
			if (hasExcludes)
				scanner.setExcludes(excludes);
			scanner.scan();
			if (getLog().isDebugEnabled())
				getLog().debug(
						MessageFormat.format("Scanned files to include: {0}",
								Arrays.toString(scanner.getIncludedFiles())));
			for (String path : scanner.getIncludedFiles())
				files.add(new File(baseDir, path));
		} else
			files = Collections.singletonList(project.getArtifact().getFile());
		return files;
	}

	/**
	 * Get map of existing downloads with names mapped to download identifiers.
	 * 
	 * @param service
	 * @param repository
	 * @return map of existing downloads
	 * @throws MojoExecutionException
	 */
	protected Map<String, Integer> getExistingDownloads(
			DownloadService service, RepositoryId repository)
			throws MojoExecutionException {
		try {
			Map<String, Integer> existing = new HashMap<String, Integer>();
			for (Download download : service.getDownloads(repository))
				if (!isEmpty(download.getName()))
					existing.put(download.getName(), download.getId());
			if (getLog().isDebugEnabled())
				getLog().debug(
						MessageFormat.format("Listed {0} existing downloads",
								existing.size()));
			return existing;
		} catch (IOException e) {
			throw new MojoExecutionException("Listing downloads failed: "
					+ getExceptionMessage(e), e);
		}
	}

	/**
	 * Deleting existing download with given id and name
	 * 
	 * @param repository
	 * @param name
	 * @param id
	 * @param service
	 * @throws MojoExecutionException
	 */
	protected void deleteDownload(RepositoryId repository, String name, int id,
			DownloadService service) throws MojoExecutionException {
		try {
			getLog().info(
					MessageFormat.format(
							"Deleting existing download: {0} ({1})", name, id));
			service.deleteDownload(repository, id);
		} catch (IOException e) {
			String prefix = MessageFormat.format(
					"Deleting existing download {0} failed: ", name);
			throw new MojoExecutionException(prefix + getExceptionMessage(e), e);
		}
	}

	public void execute() throws MojoExecutionException {
		final Log log = getLog();

		RepositoryId repository = getRepository();
		if (repository == null)
			throw new MojoExecutionException(
					"No GitHub repository (owner and name) configured");

		DownloadService service = new DownloadService(createClient());

		Map<String, Integer> existing;
		if (override)
			existing = getExistingDownloads(service, repository);
		else
			existing = Collections.emptyMap();

		List<File> files = getFiles();
		log.info(MessageFormat.format(
				"Adding {0} download(s) to {1} repository", files.size(),
				repository.generateId()));
		for (File file : files) {
			final String name = file.getName();
			final long size = file.length();
			Integer existingId = existing.get(name);
			if (existingId != null)
				deleteDownload(repository, name, existingId, service);

			Download download = new Download().setName(name).setSize(size);
			if (!isEmpty(description))
				download.setDescription(description);
			log.info(MessageFormat.format("Adding download: {0} ({1} byte(s))",
					name, size));
			try {
				DownloadResource resource = service.createResource(repository,
						download);
				service.uploadResource(resource, new FileInputStream(file),
						size);
			} catch (IOException e) {
				String prefix = MessageFormat.format(
						"Resource {0} upload failed: ", name);
				throw new MojoExecutionException(prefix
						+ getExceptionMessage(e), e);
			}
		}
	}
}