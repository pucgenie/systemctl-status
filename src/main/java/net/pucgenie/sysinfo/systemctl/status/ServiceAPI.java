package net.pucgenie.sysinfo.systemctl.status;

import java.time.Duration;
import java.util.Map;
import java.util.WeakHashMap;

import javax.servlet.http.HttpServletRequest;

import org.freedesktop.dbus.exceptions.DBusException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import de.thjom.java.systemd.Service;
import de.thjom.java.systemd.Systemd;
import de.thjom.java.systemd.Unit.Property;

@org.springframework.web.bind.annotation.RestController
@RequestMapping(path = "/service")
@org.springframework.web.bind.annotation.CrossOrigin
@lombok.extern.slf4j.Slf4j
public class ServiceAPI {

	protected final Duration waitBeforeRefresh = Duration.ofSeconds(20);
	/**
	 * String is a good candidate for Hash collections, isn't it?
	 * Using Weak implementation as a last-resort memory-capping barrier.
	 */
	protected final Map<String, ServiceData> cachedStatus = new WeakHashMap<>();

	@lombok.Data
	// @lombok.EqualsAndHashCode //all instances are unique
	@JsonIncludeProperties({"subStatus",})
	public class ServiceData {
		
		//@JsonInclude
		protected String subStatus;
		
		protected long lastRefresh = System.currentTimeMillis() - waitBeforeRefresh.toMillis();
		
		protected Service service;
		
	}
	
	/**
	 * Remove old entries where lastModified is older than 48 hours.
	 */
	@Scheduled(cron = "0 8 * * *")
	public void cleanup() {
		final long oldest = System.currentTimeMillis() - 48 * 60 * 60 * 1000;
		var allCacheEntries = cachedStatus.entrySet().iterator();
		while (allCacheEntries.hasNext()) {
			var serviceDataEntry = allCacheEntries.next();
			if (serviceDataEntry.getValue().lastRefresh < oldest) {
				allCacheEntries.remove();
			}
		}
	}

	@RequestMapping(path = "{servicename}.json", method = { RequestMethod.GET, RequestMethod.HEAD, })
	public HttpEntity<?> getServiceData(@PathVariable String servicename, HttpServletRequest req) throws DBusException {
		if (servicename.length() > 80) {
			throw new IllegalArgumentException();
		}
		synchronized (cachedStatus) {
			ServiceData serviceData = cachedStatus.get(servicename);
			if (serviceData == null) {
				cachedStatus.put(servicename,//
						serviceData = new ServiceData());
			}
			var service = serviceData.getService();
			if (service == null) {
				final var safeName = servicename + ".service";
				serviceData.setService(//
						service = Systemd.get().getManager().getService(safeName));
			}
			final long now = System.currentTimeMillis();
			boolean needsRefresh = now >= serviceData.lastRefresh + waitBeforeRefresh.toMillis();
			if (!needsRefresh && RequestMethod.HEAD.name().equals(req.getMethod()) //
					&& req.getDateHeader("If-Modified-Since") >= serviceData.lastRefresh) {
				return ResponseEntity.status(HttpStatus.NOT_MODIFIED) //
						.lastModified(serviceData.lastRefresh) //
						.build();
			}
			if (needsRefresh) {
				// provoke NPE just in case
				try {
					serviceData
							.setSubStatus(service.getUnitProperties().getString(Property.SUB_STATE));
					serviceData.lastRefresh = now;
				} catch (RuntimeException dex) {
					if (Boolean.TRUE.equals(req.getAttribute("DBusConnection_was_reset"))) {
						// prevent from endless recursion
						throw dex;
					} else {
						// to keep or not to keep for debugging?
						serviceData.setService(null);
						req.setAttribute("DBusConnection_was_reset", Boolean.TRUE);
						// TCO, please
						return getServiceData(servicename, req);
					}
				}
			} else {
				log.trace("cache hit");
			}

			return ResponseEntity.ok() //
					.cacheControl(CacheControl.maxAge(waitBeforeRefresh)) //
					.lastModified(serviceData.lastRefresh) //
					.body(serviceData);
		}
	}

}
