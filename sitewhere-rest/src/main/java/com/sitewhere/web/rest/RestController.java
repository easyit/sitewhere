/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.web.rest;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitewhere.SiteWhere;
import com.sitewhere.rest.ISiteWhereWebConstants;
import com.sitewhere.security.LoginManager;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.SiteWhereSystemException;
import com.sitewhere.spi.error.ErrorCode;
import com.sitewhere.spi.error.ErrorLevel;
import com.sitewhere.spi.server.lifecycle.LifecycleStatus;
import com.sitewhere.spi.server.tenant.ISiteWhereTenantEngine;
import com.sitewhere.spi.tenant.ITenant;
import com.sitewhere.spi.tenant.TenantNotAvailableException;
import com.sitewhere.spi.user.IUser;
import com.sitewhere.spi.user.SiteWhereRoles;

/**
 * Base class for common REST controller functionality.
 * 
 * @author Derek Adams
 */
public class RestController {

	/** Static logger instance */
	private static Logger LOGGER = Logger.getLogger(RestController.class);

	/**
	 * Get a tenant based on the authentication token passed.
	 * 
	 * @param request
	 * @return
	 * @throws SiteWhereException
	 */
	protected ITenant getTenant(HttpServletRequest request) throws SiteWhereException {
		String token = getTenantAuthToken(request);
		ITenant match = SiteWhere.getServer().getTenantByAuthToken(token);
		if (match == null) {
			throw new SiteWhereSystemException(ErrorCode.InvalidTenantAuthToken, ErrorLevel.ERROR);
		}
		ISiteWhereTenantEngine engine = SiteWhere.getServer().getTenantEngine(match.getId());
		if ((engine == null) || (engine.getEngineState().getLifecycleStatus() != LifecycleStatus.Started)) {
			throw new TenantNotAvailableException();
		}

		String username = LoginManager.getCurrentlyLoggedInUser().getUsername();
		if (match.getAuthorizedUserIds().contains(username)) {
			return match;
		}
		throw new SiteWhereSystemException(ErrorCode.NotAuthorizedForTenant, ErrorLevel.ERROR);
	}

	/**
	 * Verify that the current user is authorized to interact with the given tenant id.
	 * 
	 * @param tenantId
	 * @throws SiteWhereException
	 */
	protected ITenant assureAuthorizedTenantId(String tenantId) throws SiteWhereException {
		ITenant tenant = SiteWhere.getServer().getTenantManagement().getTenantById(tenantId);
		if (tenant == null) {
			throw new SiteWhereSystemException(ErrorCode.InvalidTenantId, ErrorLevel.ERROR);
		}
		return assureAuthorizedTenant(tenant);
	}

	/**
	 * Verify that the current user is authorized to interact with the given tenant.
	 * 
	 * @param tenant
	 * @return
	 * @throws SiteWhereException
	 */
	protected ITenant assureAuthorizedTenant(ITenant tenant) throws SiteWhereException {
		IUser user = LoginManager.getCurrentlyLoggedInUser();

		// Tenant administrators do not have to be in the list of authorized users.
		if (user.getAuthorities().contains(SiteWhereRoles.AUTH_ADMINISTER_TENANTS)) {
			return tenant;
		}

		// Tenant self-editors have to be in the list of authorized users.
		if (!tenant.getAuthorizedUserIds().contains(user.getUsername())) {
			throw new SiteWhereSystemException(ErrorCode.NotAuthorizedForTenant, ErrorLevel.ERROR);
		}
		return tenant;
	}

	/**
	 * Get tenant authentication token from the servlet request.
	 * 
	 * @param request
	 * @return
	 * @throws SiteWhereException
	 */
	protected String getTenantAuthToken(HttpServletRequest request) throws SiteWhereException {
		String token = request.getHeader(ISiteWhereWebConstants.HEADER_TENANT_TOKEN);
		if (token == null) {
			token = request.getParameter(ISiteWhereWebConstants.REQUEST_TENANT_TOKEN);
			if (token == null) {
				throw new SiteWhereSystemException(ErrorCode.MissingTenantAuthToken, ErrorLevel.ERROR,
						HttpServletResponse.SC_UNAUTHORIZED);
			}
		}
		return token;
	}

	/**
	 * Send message back to called indicating successful add.
	 * 
	 * @param response
	 */
	protected void handleSuccessfulAdd(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_CREATED);
		try {
			response.flushBuffer();
		} catch (IOException e) {
			// Ignore failed flush.
		}
	}

	/**
	 * Handles a system exception by setting the HTML response code and response headers.
	 * 
	 * @param e
	 * @param response
	 */
	@ExceptionHandler
	protected void handleSystemException(SiteWhereException e, HttpServletRequest request,
			HttpServletResponse response) {
		try {
			String flexMode = request.getHeader("X-SiteWhere-Error-Mode");
			if (flexMode != null) {
				ObjectMapper mapper = new ObjectMapper();
				mapper.writeValue(response.getOutputStream(), e);
				response.flushBuffer();
			} else {
				if (e instanceof SiteWhereSystemException) {
					SiteWhereSystemException sse = (SiteWhereSystemException) e;
					String combined = sse.getCode() + ":" + e.getMessage();
					response.setHeader(ISiteWhereWebConstants.HEADER_SITEWHERE_ERROR, e.getMessage());
					response.setHeader(ISiteWhereWebConstants.HEADER_SITEWHERE_ERROR_CODE,
							String.valueOf(sse.getCode()));
					if (sse.hasHttpResponseCode()) {
						response.sendError(sse.getHttpResponseCode(), combined);
					} else {
						response.sendError(HttpServletResponse.SC_BAD_REQUEST, combined);
					}
				} else {
					response.setHeader(ISiteWhereWebConstants.HEADER_SITEWHERE_ERROR, e.getMessage());
					response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
				}
				LOGGER.error("Exception thrown during REST processing.", e);
			}
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * Handles uncaught runtime exceptions such as null pointers.
	 * 
	 * @param e
	 * @param response
	 */
	@ExceptionHandler
	protected void handleRuntimeException(RuntimeException e, HttpServletResponse response) {
		LOGGER.error("Unhandled runtime exception.", e);
		try {
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			LOGGER.error("Unhandled runtime exception.", e);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * Handles exception thrown when a tenant operation is requested on an unavailable
	 * tenant.
	 * 
	 * @param e
	 * @param response
	 */
	@ExceptionHandler
	protected void handleTenantNotAvailable(TenantNotAvailableException e, HttpServletResponse response) {
		LOGGER.error("Operation invoked on unavailable tenant.", e);
		try {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
					"The requested tenant is not available.");
		} catch (IOException e1) {
			LOGGER.error(e1);
		}
	}

	/**
	 * Handles exceptions generated if {@link Secured} annotations are not satisfied.
	 * 
	 * @param e
	 * @param response
	 */
	@ExceptionHandler
	protected void handleAccessDenied(AccessDeniedException e, HttpServletResponse response) {
		try {
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			LOGGER.error("Access denied.", e);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * Handles situations where user does not pass exprected content for a POST.
	 * 
	 * @param e
	 * @param response
	 */
	@ExceptionHandler
	protected void handleMissingContent(HttpMessageNotReadableException e, HttpServletResponse response) {
		try {
			LOGGER.error("Error handling REST request..", e);
			response.sendError(HttpServletResponse.SC_BAD_REQUEST,
					"No body content passed for POST request.");
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}
}