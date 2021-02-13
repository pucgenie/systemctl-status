package net.pucgenie.sysinfo.systemctl.status;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;

import javax.servlet.http.HttpServletRequest;

import org.freedesktop.dbus.exceptions.DBusException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import de.thjom.java.systemd.Systemd;

@RestController
@RequestMapping(path = "/service")
public class Service {

	protected Duration waitBeforeRefresh = Duration.ofSeconds(60);
	/**
	 * String is a good candidate for Hash collections, isn't it?
	 * Using Weak implementation as a last-resort memory-capping barrier.
	 */
	protected final Map<String, ServiceData> cachedStatus = new WeakHashMap<>();

	@lombok.Data
	// @lombok.EqualsAndHashCode //all instances are unique
	public class ServiceData {
		protected String subStatus;
		protected long lastRefresh = System.currentTimeMillis() - waitBeforeRefresh.toMillis();
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
				cachedStatus.put(servicename, serviceData = new ServiceData());
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
				try {
					// evtl. loadUnit verwenden
					serviceData
							.setSubStatus(Systemd.get().getManager().getUnit(servicename + ".service").getSubState());
					serviceData.lastRefresh = now;
				} catch (DBusException e) {
//				return ResponseEntity.unprocessableEntity()
//						.cacheControl(CacheControl.noCache())
//						.body(e);
					throw e;
				}
			} else {
				// org.slf4j.LoggerFactory.getLogger(getClass()).info("cache hit");
			}

			return ResponseEntity.ok() //
					.cacheControl(CacheControl.maxAge(waitBeforeRefresh)) //
					.lastModified(serviceData.lastRefresh) //
					.body(serviceData);
		}
	}

}
