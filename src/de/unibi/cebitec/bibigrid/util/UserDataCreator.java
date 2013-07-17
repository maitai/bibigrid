/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package de.unibi.cebitec.bibigrid.util;

import de.unibi.cebitec.bibigrid.model.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author alueckne, jkrueger (at) cebitec.uni-bielefeld.de
 *
 *
 *
 */
public class UserDataCreator {

    public static final Logger log = LoggerFactory.getLogger(UserDataCreator.class);

    /**
     * Creates slaveUserData content.
     *
     *
     * Changes JK :
     * <ul>
     * <li> use StringBuilder instead of String concatenation ;-) </li>
     * <li> remove unnecessary sudo's - UserData is executed as root </li>
     * </ul>
     *
     *
     * @param masterIp
     * @param masterDns
     * @param slaveDeviceMapper
     * @param slaveNfsMounts
     * @param ephemerals
     * @return
     */
    public static String forSlave(String masterIp, String masterDns, DeviceMapper slaveDeviceMapper, List<String> slaveNfsMounts, int ephemerals) {
        StringBuilder slaveUserData = new StringBuilder();
        slaveUserData.append("#!/bin/sh\n");
        slaveUserData.append("sleep 5\n");
        slaveUserData.append("mkdir -p /vol/spool/\n");
        slaveUserData.append("mkdir -p /vol/scratch/\n");
        slaveUserData.append("echo ").append(masterIp).append(" > /var/lib/gridengine/default/common/act_qmaster\n");
        slaveUserData.append("echo ").append(masterIp).append(" ").append(masterDns).append(" >> /etc/hosts\n");
        slaveUserData.append("pid=`ps acx | grep sge_execd | cut -c1-6`\n");
        slaveUserData.append("if [ -n $pid ]; then\n");
        slaveUserData.append("        kill $pid;\n");
        slaveUserData.append("fi;\n");
        slaveUserData.append("sleep 1\n");
        slaveUserData.append("while test $(ps acx | grep sge_execd | wc -l) -eq 0; do\n");
        slaveUserData.append("        service gridengine-exec start\n");
        slaveUserData.append("        sleep 35\n");
        slaveUserData.append("done\n");
        slaveUserData.append("sed -i s/MASTER_IP/").append(masterIp).append("/g /etc/ganglia/gmond.conf\n");
        slaveUserData.append("service ganglia-monitor restart \n");


        if (ephemerals == 1) {
            slaveUserData.append("sudo umount /mnt\n");
            slaveUserData.append("sudo mount /dev/xvdb /vol/scratch\n");
        } else if (ephemerals >= 2) {
            // if 2 or more ephemerals are available use 2 as a RAID system
            slaveUserData.append("sudo umount /mnt\n");

            switch (ephemerals) {
                case 2: {
                    slaveUserData.append("yes | mdadm --create /dev/md0 --level=0 -c256 --raid-devices=2 /dev/xvdb /dev/xvdc\n");
                    break;
                }
                case 4: {
                    slaveUserData.append("yes | mdadm --create /dev/md0 --level=0 -c256 --raid-devices=4 /dev/xvdb /dev/xvdc /dev/xvdd /dev/xvde\n");
                    break;
                }
            }

            slaveUserData.append("echo 'DEVICE /dev/xvdb /dev/xvdc' > /etc/mdadm.conf\n");
            slaveUserData.append("mdadm --detail --scan >> /etc/mdadm.conf\n");

            slaveUserData.append("blockdev --setra 65536 /dev/md0\n");
            slaveUserData.append("mkfs.xfs -f /dev/md0\n");
            slaveUserData.append("mount -t xfs -o noatime /dev/md0 /vol/scratch\n");
            slaveUserData.append("chown ubuntu:ubuntu /vol/scratch \n");


        }
        slaveUserData.append("chown ubuntu:ubuntu /vol/ \n");
        slaveUserData.append("mount -t nfs4 -o proto=tcp,port=2049 ").append(masterIp).append(":/vol/spool /vol/spool\n");

        for (String e : slaveDeviceMapper.getSnapshotIdToMountPoint().keySet()) {
            slaveUserData.append("mkdir -p ").append(slaveDeviceMapper.getSnapshotIdToMountPoint().get(e)).append("\n");
            slaveUserData.append("mount ").append(slaveDeviceMapper.getRealDeviceNameforMountPoint(slaveDeviceMapper.getSnapshotIdToMountPoint().get(e))).append(" ").append(slaveDeviceMapper.getSnapshotIdToMountPoint().get(e)).append("\n");
        }
        if (!slaveNfsMounts.isEmpty()) {
            for (String share : slaveNfsMounts) {
                slaveUserData.append("sudo mkdir -p ").append(share).append("\n");
                slaveUserData.append("mount -t nfs4 -o proto=tcp,port=2049 ").append(masterIp).append(":").append(share).append(" ").append(share).append("\n");
            }
        }

        return new String(Base64.encodeBase64(slaveUserData.toString().getBytes()));
    }

    /**
     * Creates masterUserData content.
     *
     *
     * Changes JK :
     * <ul>
     * <li> Encode earlyscript as base64 string to avoid shell interpretation of
     * special chars like ($,
     *
     * @,`,&) </li>
     * <li> Limit earlyscript length to 10K chars (base64 encoded), roughly 6.6K
     * absolute size </li>
     * <li> use StringBuilder instead of String concatenation ;-) </li>
     * <li> remove unnecessary sudo's - UserData is executed as root </li>
     * <li> execute earlyscript as ubuntu user </li>
     * </ul>
     * @param ephemeralamount
     * @param masterNfsShares
     * @param masterDeviceMapper
     * @param cfg
     * @return
     */
    public static String masterUserData(int ephemeralamount, List<String> masterNfsShares, DeviceMapper masterDeviceMapper, Configuration cfg) {
        StringBuilder masterUserData = new StringBuilder();

        masterUserData.append("#!/bin/sh\n").append("sleep 5\n");

        // if 1 ephemeral is available mount it as /vol/spool
        if (ephemeralamount == 1) {
            masterUserData.append("umount /mnt\n"); // 
            masterUserData.append("mount /dev/xvdb /vol/\n");
        } else if (ephemeralamount >= 2) {
            // if 2 or more ephemerals are available use 2 as a RAID system
            masterUserData.append("umount /mnt\n");
            switch (ephemeralamount) {
                case 2: {
                    masterUserData.append("yes | mdadm --create /dev/md0 --level=0 -c256 --raid-devices=2 /dev/xvdb /dev/xvdc\n");
                    break;
                }
                case 4: {
                    masterUserData.append("yes | mdadm --create /dev/md0 --level=0 -c256 --raid-devices=4 /dev/xvdb /dev/xvdc /dev/xvdd /dev/xvde\n");
                    break;
                }
            }
            masterUserData.append("echo 'DEVICE /dev/xvdb /dev/xvdc' > /etc/mdadm.conf\n");
            masterUserData.append("mdadm --detail --scan >> /etc/mdadm.conf\n");

            masterUserData.append("blockdev --setra 65536 /dev/md0\n");
            masterUserData.append("mkfs.xfs -f /dev/md0\n");
            masterUserData.append("mount -t xfs -o noatime /dev/md0 /vol/\n");

        }

        masterUserData.append("mkdir -p " + "/vol/spool/" + "\n");
        masterUserData.append("chmod 777 " + "/vol/spool/" + "\n");
        masterUserData.append("echo '" + "/vol/spool/" + " 10.0.0.0/8(rw,nohide,insecure,no_subtree_check,async)'>> /etc/exports\n");

        masterUserData.append("mkdir -p " + "/vol/scratch/" + "\n");
        masterUserData.append("chown ubuntu:ubuntu /vol/ \n");
        masterUserData.append("chown ubuntu:ubuntu /vol/scratch \n");
        for (String e : masterDeviceMapper.getSnapshotIdToMountPoint().keySet()) {
            masterUserData.append("mkdir -p ").append(masterDeviceMapper.getSnapshotIdToMountPoint().get(e)).append("\n");
            masterUserData.append("mount ").append(masterDeviceMapper.getRealDeviceNameforMountPoint(masterDeviceMapper.getSnapshotIdToMountPoint().get(e))).append(" ").append(masterDeviceMapper.getSnapshotIdToMountPoint().get(e)).append("\n");
        }
        for (String mastershare : masterNfsShares) {
            masterUserData.append("mkdir -p ").append(mastershare).append("\n");
            masterUserData.append("chmod 777 ").append(mastershare).append("\n");
            masterUserData.append("echo '").append(mastershare).append(" 10.0.0.0/8(rw,nohide,insecure,no_subtree_check,async)'>> /etc/exports\n");
        }
        masterUserData.append("/etc/init.d/nfs-kernel-server restart\n");

        if (cfg.getEearlyShellScriptFile() != null) {
            try {

                String base64 = new String(Base64.encodeBase64(Files.readAllBytes(cfg.getEearlyShellScriptFile())));

                if (base64.length() > 10000) {
                    log.info("Early shell script file too large  (base64 encoded size exceeds 10000 chars)");
                } else {
                    masterUserData.append("echo ").append(base64).append(" | base64 --decode  | sudo -u ubuntu bash - 2>&1 >> /var/log/earlyshellscript.log &\n");
                }


            } catch (IOException e) {
                log.info("Early shell script could not be read.");
            }
        }

        return new String(Base64.encodeBase64(masterUserData.toString().getBytes()));
    }
}
