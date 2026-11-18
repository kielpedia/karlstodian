# Deploying

Karlstodian runs in its own Debian LXC as the systemd service `karlstodian`, from
`/opt/karlstodian` (`karlstodian.jar` + `data/`), on port 8000. LAN only: it has no login.

## Updates: automatic

Every merge to `main` is built by CI and published as a GitHub release (`build-<n>`). On the
server, `karlstodian-update.timer` checks every 5 minutes, installs a new release (checksum
checked), restarts and health-checks it, and rolls back to the previous jar if it doesn't come up.

- See what it did: `journalctl -u karlstodian-update`
- Update now: `karlstodian-update`
- Running version: `cat /opt/karlstodian/VERSION`

## First install (or reinstall)

Needs Debian 12 or 13 with network access; 1 core, 1 GB RAM and 4 GB disk are enough. The
installer adds Java 21, curl and jq, the service and the update timer. It brings your `data/`
(settings, API keys, database) from the PC only if the server has none yet.

1. On the PC, build the bundle (jar + installer + updater + your `data/`). It holds your API
   keys, so it stays under `build/` and is never committed:

   ```
   gradlew deployBundle            # -> build/karlstodian-deploy.tgz
   scp build/karlstodian-deploy.tgz root@<proxmox-host>:/root/
   ```

2. On the Proxmox host:

   ```
   pct push <ctid> /root/karlstodian-deploy.tgz /root/karlstodian-deploy.tgz && rm /root/karlstodian-deploy.tgz
   pct exec <ctid> -- bash -c 'cd /root && tar -xzf karlstodian-deploy.tgz && bash karlstodian-deploy/install-karlstodian.sh'
   ```

   It ends with `Karlstodian is running: http://<ip>:8000` and deletes the bundle from the CT.
   Within ~5 minutes the updater replaces the PC-built jar with the newest release.

## Notes

- The nightly scan uses the container's timezone: `timedatectl set-timezone <Area/City>`, then
  `systemctl restart karlstodian`.
- Logs: `journalctl -u karlstodian -f`.
