package it.pgp.xfiles.roothelperclient;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import it.pgp.Native;
import it.pgp.xfiles.BrowserItem;
import it.pgp.xfiles.CopyListUris;
import it.pgp.xfiles.CopyMoveListPathContent;
import it.pgp.xfiles.MainActivity;
import it.pgp.xfiles.R;
import it.pgp.xfiles.dialogs.XFilesRemoteSessionsManagementActivity;
import it.pgp.xfiles.enums.FileOpsErrorCodes;
import it.pgp.xfiles.roothelperclient.reqs.ListOfPathPairs_rq;
import it.pgp.xfiles.service.BaseBackgroundTask;
import it.pgp.xfiles.service.NonInteractiveXFilesRemoteTransferTask;
import it.pgp.xfiles.utils.ContentProviderUtils;
import it.pgp.xfiles.utils.Misc;
import it.pgp.xfiles.utils.ProgressConflictHandler;
import it.pgp.xfiles.utils.pathcontent.BasePathContent;
import it.pgp.xfiles.utils.pathcontent.XFilesRemotePathContent;
import it.pgp.xfiles.utils.popupwindow.PopupWindowUtils;

/**
 * Created by pgp on 20/09/17
 *
 * Remote client manager that performs remote action via the connected RH remote client session
 */

public class RemoteClientManager {

    private static final long EOF_ind = ProgressConflictHandler.Status.EOF.getStatus(); // end of file
    private static final long EOFs_ind = ProgressConflictHandler.Status.EOFs.getStatus(); // end of files

    // use only serverHost as key for now
    public final Map<String,RemoteManager> fastClients = new ConcurrentHashMap<>();
    public final Map<String,RemoteManager> longTermClients = new ConcurrentHashMap<>();

    public synchronized void closeAllSessions() {
        for (RemoteManager rm : fastClients.values()) rm.close();
        for (RemoteManager rm : longTermClients.values()) rm.close();
        fastClients.clear();
        longTermClients.clear();
        if (XFilesRemoteSessionsManagementActivity.CtoSAdapter != null) {
            XFilesRemoteSessionsManagementActivity.CtoSAdapter.syncFromActivity();
        }
    }

    // progress methods and task field put here in order to avoid putting them also in RemoteServerManager, which extends RemoteManager and doesn't publish progress

    public BaseBackgroundTask progressTask;
    public ContentResolver contentResolver;

    private void initProgressSupport(BaseBackgroundTask task) {
        this.progressTask = task;
    }

    private void destroyProgressSupport() {
        this.progressTask = null;
    }

    public RemoteManager createAndConnectClient(String serverHost) {
        try {
            RemoteManager client = new RemoteManager();
            client.o.write(ControlCodes.REMOTE_CONNECT.getValue());

            // send host string with length
            byte[] serverHost_ = serverHost.getBytes();
            byte[] hostLen_ = Misc.castUnsignedNumberToBytes(serverHost_.length,1);
            client.o.write(hostLen_);
            client.o.write(serverHost_);
            // send port
            byte[] port_ = Misc.castUnsignedNumberToBytes(XFilesRemotePathContent.defaultRHRemoteServerPort,2);
            client.o.write(port_);

            int resp = client.receiveBaseResponse();
            if (resp != 0) {
                client.close();
                return null;
            }
            // ok, streams connected to RH in remote client mode

            // receive TLS session hash
            client.i.readFully(client.tlsSessionHash);
            Log.d(this.getClass().getName(),"Client TLS session shared secret hash: "+Misc.toHexString(client.tlsSessionHash));

            // show the visual hash of the shared TLS master secret
            if (MainActivity.mainActivity != null) {
                MainActivity.mainActivity.runOnUiThread(()->
//                        new HashViewDialog(MainActivity.mainActivity,client.tlsSessionHash,true).show());
                PopupWindowUtils.createAndShowHashViewPopupWindow(
                        MainActivity.mainActivity,
                        client.tlsSessionHash,
                        true,
                        MainActivity.mainActivity.findViewById(R.id.activity_main)));
            }

            return client;
        }
        catch (IOException e) {
            return null;
        }
    }

    public RemoteManager getClient(String serverHost, boolean isFastClient) {
        RemoteManager client;
        if (isFastClient) {
            if (fastClients.containsKey(serverHost)) return fastClients.get(serverHost);
            client = createAndConnectClient(serverHost);
            if (client == null) return null;
            fastClients.put(serverHost,client);
        }
        else {
            if (longTermClients.containsKey(serverHost)) return longTermClients.get(serverHost);
            client = createAndConnectClient(serverHost);
            if (client == null) return null;
            longTermClients.put(serverHost,client);
        }
        if (XFilesRemoteSessionsManagementActivity.CtoSAdapter != null) {
            XFilesRemoteSessionsManagementActivity.CtoSAdapter.syncFromActivity();
        }
        return client;
    }

    private void publishReceivedProgress(long progress, long totalSizeSoFar, long totalSize, long currentFileSize) {
        Log.e("XREProgress","It's progress: "+progress);
        if (this.progressTask != null) {
            this.progressTask.publishProgressWrapper(
                    (int) Math.round((totalSizeSoFar+progress) * 100.0 / totalSize),
                    (int) Math.round(progress * 100.0 / currentFileSize)
            );
        }
    }

    // TODO make RemoteClientManager implementor of FileOperationHelperUsingPathContent and remove duplicated code in RootHelperClientUsingPathContent
    // TODO use StreamsPair and getStreams(...) in RootHelperClientUsingPathContent, remove duplicated methods from here

    public FileOpsErrorCodes transferItems(CopyMoveListPathContent items, BasePathContent destDir, ControlCodes action, NonInteractiveXFilesRemoteTransferTask progressTask, ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
        // get communication endpoint
        String clientKey;
        switch (action) {
            case ACTION_DOWNLOAD:
                clientKey = ((XFilesRemotePathContent)items.parentDir).serverHost;
                break;
            case ACTION_UPLOAD:
                clientKey = ((XFilesRemotePathContent)destDir).serverHost;
                break;
            default:
                throw new RuntimeException("Unexpected action in remote transfer task");
        }

        RemoteManager client = getClient(clientKey,false);
        if (client == null) {
            // unable to connect
            return FileOpsErrorCodes.CONNECTION_ERROR;
        }

        initProgressSupport(progressTask);

        if (items instanceof CopyListUris) {
            /* - customize ACTION_UPLOAD request byte with flags 111
             * - query size for each uri, accumulate total size and list with individual sizes
             * - get file descriptors one by one with content resolver and send them via UDS (LocalSocket)
             * - receive progress for each file
             */
            byte customizedRq = action.getValue();
            customizedRq ^= (7 << 5); // flags: 111

            try {
                FileDescriptor uds = client.ls.getFileDescriptor();
                Field nativeField = uds.getClass().getDeclaredField("descriptor");
                nativeField.setAccessible(true);
                int nativeUds = (int)nativeField.get(uds);

                List<Uri> uris = new ArrayList<>();
                List<String> names = new ArrayList<>();
                List<Long> sizes = new ArrayList<>();
                long totalSize = 0;
                long currentFileSize = 0;
                for (String uriString : ((CopyListUris)items).contentUris) {
                    Uri uri = Uri.parse(uriString);
                    uris.add(uri);
                    names.add(ContentProviderUtils.getName(contentResolver,uri));
                    currentFileSize = ContentProviderUtils.getSize(contentResolver,uri);
                    sizes.add(currentFileSize);
                    totalSize += currentFileSize;
                }

                client.o.write(customizedRq);
                client.o.write(Misc.castUnsignedNumberToBytes(totalSize,8));
                long totalSizeSoFar = 0;


                for (int i=0;i<uris.size();i++) {
                    Log.e("XREProgress","Sending file info and descriptor for "+names.get(i));
                    Misc.sendStringWithLen(client.o,destDir.dir+"/"+names.get(i));
                    client.o.write(Misc.castUnsignedNumberToBytes(sizes.get(i),8));
                    int fdToSend = contentResolver.openFileDescriptor(uris.get(i),"r").detachFd();
                    Native.sendDetachedFD(nativeUds,fdToSend);

                    // receive progress
                    long tmp;
                    do {
                        tmp = Misc.receiveTotalOrProgress(client.i);
                        publishReceivedProgress(tmp,totalSizeSoFar,totalSize,currentFileSize);
                    }
                    while(tmp!=EOF_ind);
                    totalSizeSoFar += currentFileSize;
                }
                client.o.write(new byte[2]); // EOL

            }
            catch (Exception e) {
                e.printStackTrace();
                return FileOpsErrorCodes.TRANSFER_ERROR;
            }

            return FileOpsErrorCodes.TRANSFER_OK;
        }
        else {
            ArrayList<String> v_fx = new ArrayList<>();
            ArrayList<String> v_fy = new ArrayList<>();

            for (BrowserItem fname : items.files) {
                v_fx.add(items.parentDir.dir+"/"+fname.getFilename());
                v_fy.add(destDir.dir+"/"+fname.getFilename());
            }

            ListOfPathPairs_rq r = new ListOfPathPairs_rq(v_fx,v_fy);
            r.requestType = action;

            try {
                r.write(client.o);

                // receive total number of files for outer progress
                final long totalFileCount = Misc.receiveTotalOrProgress(client.i);
                final long totalSize = Misc.receiveTotalOrProgress(client.i);
                Log.e("XREProgress","Total size is "+totalSize);
                long currentFileCount = 0;
                long totalSizeSoFar = 0; // rounded to last completed file
//            long currentFileSize = EOF_ind; // legacy, maybe breaks things
                long currentFileSize = 0; // placeholder, just to avoid uninitialized error

                boolean hasReceivedSizeForCurrentFile = false;

                // receive progress for single files, increment outer progress bar by 1 on EOF_ind progress

                for (;;) {
                    long tmp = Misc.receiveTotalOrProgress(client.i);

                    if (tmp == EOF_ind) {
                        Log.e("XREProgress","Received EOF, file count before: "+currentFileCount);
                        hasReceivedSizeForCurrentFile = false;
                        currentFileCount++;
                        totalSizeSoFar += currentFileSize;
                        this.progressTask.publishProgressWrapper(
                                (int)Math.round(totalSizeSoFar*100.0/totalSize),
                                0
                        );
                    }
                    else if (tmp == EOFs_ind) {
                        Log.e("XREProgress","Received EOFs");
                        break;
                    }
                    else {
                        Log.e("XREProgress","Received progress or size");
                        if (hasReceivedSizeForCurrentFile) {
                            Log.e("XREProgress","It's progress: "+tmp);
                            publishReceivedProgress(tmp,totalSizeSoFar,totalSize,currentFileSize);
                        }
                        else {
                            Log.e("XREProgress","It's size: "+tmp);
                            // here, tmp is current file's size, before starting copying current file
                            currentFileSize = tmp;
                            hasReceivedSizeForCurrentFile = true;
                            if (this.progressTask != null) {
                                this.progressTask.publishProgressWrapper(
                                        (int) Math.round(totalSizeSoFar * 100.0 / totalSize),
                                        0
                                );
                            }
                        }
                    }
                }
                return FileOpsErrorCodes.TRANSFER_OK;
            }
            catch (IOException e) {
                client.close();
                longTermClients.remove(clientKey);
                return FileOpsErrorCodes.TRANSFER_ERROR;
            }
            finally {
                destroyProgressSupport();
            }
        }
    }
}
