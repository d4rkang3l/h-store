package edu.brown.hstore.callbacks;

import java.util.Collection;

import org.apache.log4j.Logger;

import com.google.protobuf.RpcCallback;

import edu.brown.hstore.Hstore;
import edu.brown.hstore.Hstore.Status;
import edu.brown.hstore.Hstore.TransactionInitResponse;
import edu.brown.hstore.Hstore.TransactionMapResponse;
import edu.brown.hstore.Hstore.TransactionMapResponse.Builder;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.hstore.HStoreSite;
import edu.brown.hstore.dtxn.MapReduceTransaction;
import edu.brown.hstore.util.MapReduceHelperThread;

/**
 * This is callback is used on the remote side of a TransactionMapRequest
 * so that the network-outbound callback is not invoked until all of the partitions
 * at this HStoreSite is finished with the Map phase. 
 * @author pavlo
 */
public class TransactionMapWrapperCallback extends BlockingCallback<Hstore.TransactionMapResponse, Integer> {
	private static final Logger LOG = Logger.getLogger(TransactionMapWrapperCallback.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
	
	private Hstore.TransactionMapResponse.Builder builder = null;
	private MapReduceTransaction ts;
	
	public Hstore.TransactionMapResponse.Builder getBuilder() {
        return builder;
    }

    public TransactionMapWrapperCallback(HStoreSite hstore_site) {
		super(hstore_site, false);
	}
	
	public void init(MapReduceTransaction ts, RpcCallback<Hstore.TransactionMapResponse> orig_callback) {
	    assert(this.isInitialized() == false) :
            String.format("Trying to initialize %s twice! [origTs=%s, newTs=%s]",
                          this.getClass().getSimpleName(), this.ts, ts);
        if (debug.get())
            LOG.debug("Starting new " + this.getClass().getSimpleName() + " for " + ts);
        this.ts = ts;
		this.builder = Hstore.TransactionMapResponse.newBuilder()
        					 .setTransactionId(ts.getTransactionId())
        					 .setStatus(Hstore.Status.OK);
		super.init(ts.getTransactionId(), hstore_site.getLocalPartitionIds().size(), orig_callback);
	}
	
	@Override
	protected void abortCallback(Status status) {
		if (debug.get())
            LOG.debug(String.format("Txn #%d - Aborting %s with status %s",
                                    this.getTransactionId(), this.getClass().getSimpleName(), status));
        this.builder.setStatus(status);
        Collection<Integer> localPartitions = hstore_site.getLocalPartitionIds();
        for (Integer p : this.hstore_site.getLocalPartitionIds()) {
            if (localPartitions.contains(p) && this.builder.getPartitionsList().contains(p) == false) {
                this.builder.addPartitions(p.intValue());
            }
        } // FOR
        this.unblockCallback();
	}

	@Override
	protected void finishImpl() {
		this.builder = null;
		this.ts = null;
	}
	
    @Override
    public boolean isInitialized() {
        return ( this.ts != null && this.builder != null && super.isInitialized());
    }

	@Override
	protected int runImpl(Integer partition) {
		if (this.isAborted() == false)
            this.builder.addPartitions(partition.intValue());
		assert(this.ts != null) :
            String.format("Missing MapReduceTransaction handle for txn #%d", this.ts.getTransactionId());
		
        return 1;
	}

	@Override
	protected void unblockCallback() {
		if (debug.get()) {
            LOG.debug(String.format("Txn #%d - Sending %s to %s with status %s",
                                    this.getTransactionId(),
                                    TransactionMapResponse.class.getSimpleName(),
                                    this.getOrigCallback().getClass().getSimpleName(),
                                    this.builder.getStatus()));
        }
        assert(this.getOrigCounter() == builder.getPartitionsCount()) :
            String.format("The %s for txn #%d has results from %d partitions but it was suppose to have %d.",
                          builder.getClass().getSimpleName(), this.getTransactionId(), builder.getPartitionsCount(), this.getOrigCounter());
        assert(this.getOrigCallback() != null) :
            String.format("The original callback for txn #%d is null!", this.getTransactionId());
        
        
        //  Get the MapReduceHelperThread object from the HStoreSite
        //  Pass the MapReduceTransaction handle to the helper thread to perform the shuffle operation
        //  Move this to be execute after the SHUFFLE phase is finished --> this.getOrigCallback().run(this.builder.build());
        
        MapReduceHelperThread mr_helper = this.hstore_site.getMapReduceHelper();
        ts.setShufflePhase();
        
        if (debug.get()) 
            LOG.debug(String.format("Txn #%d - I am swithing to SHUFFLE phase, go to MR_helper thread",this.getTransactionId()));
        // enqueue this MapReduceTransaction to do shuffle work
        mr_helper.queue(this.ts);
	}
	
	public void runOrigCallback() {
        this.getOrigCallback().run(this.builder.build());
    }

	
}
