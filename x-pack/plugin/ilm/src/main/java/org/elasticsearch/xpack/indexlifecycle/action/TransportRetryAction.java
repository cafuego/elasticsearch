/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.indexlifecycle.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleExecutionState;
import org.elasticsearch.xpack.core.indexlifecycle.Step.StepKey;
import org.elasticsearch.xpack.core.indexlifecycle.action.RetryAction;
import org.elasticsearch.xpack.core.indexlifecycle.action.RetryAction.Request;
import org.elasticsearch.xpack.core.indexlifecycle.action.RetryAction.Response;
import org.elasticsearch.xpack.indexlifecycle.IndexLifecycleService;

import java.io.IOException;

public class TransportRetryAction extends TransportMasterNodeAction<Request, Response> {

    IndexLifecycleService indexLifecycleService;

    @Inject
    public TransportRetryAction(TransportService transportService, ClusterService clusterService, ThreadPool threadPool,
                                ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                IndexLifecycleService indexLifecycleService) {
        super(RetryAction.NAME, transportService, clusterService, threadPool, actionFilters, indexNameExpressionResolver,
                Request::new);
        this.indexLifecycleService = indexLifecycleService;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected Response newResponse() {
        throw new UnsupportedOperationException("usage of Streamable is to be replaced by Writeable");
    }

    @Override
    protected Response read(StreamInput in) throws IOException {
        return new Response(in);
    }

    @Override
    protected void masterOperation(Task task, Request request, ClusterState state, ActionListener<Response> listener) {
        clusterService.submitStateUpdateTask("ilm-re-run",
            new AckedClusterStateUpdateTask<Response>(request, listener) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return indexLifecycleService.moveClusterStateToFailedStep(currentState, request.indices());
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    for (String index : request.indices()) {
                        IndexMetaData idxMeta = newState.metaData().index(index);
                        LifecycleExecutionState lifecycleState = LifecycleExecutionState.fromIndexMetadata(idxMeta);
                        StepKey retryStep = new StepKey(lifecycleState.getPhase(), lifecycleState.getAction(), lifecycleState.getStep());
                        if (idxMeta == null) {
                            // The index has somehow been deleted - there shouldn't be any opportunity for this to happen, but just in case.
                            logger.debug("index [" + index + "] has been deleted after moving to step [" +
                                lifecycleState.getStep() + "], skipping async action check");
                            return;
                        }
                        indexLifecycleService.maybeRunAsyncAction(newState, idxMeta, retryStep);
                    }
                }

                @Override
                protected Response newResponse(boolean acknowledged) {
                    return new Response(acknowledged);
                }
            });
    }

    @Override
    protected ClusterBlockException checkBlock(Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
