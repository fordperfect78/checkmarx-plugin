package com.checkmarx.jenkins;

import hudson.Extension;
import hudson.Functions;
import hudson.PluginWrapper;
import hudson.matrix.MatrixProject;
import hudson.model.*;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.util.*;
import org.apache.log4j.Logger;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.StackedAreaRenderer;
import org.jfree.data.category.CategoryDataset;
import org.jfree.ui.RectangleInsets;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.awt.*;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;

/**
 * Created with IntelliJ IDEA.
 * User: denis
 * Date: 25/11/2013
 * Time: 12:07
 * Description:
 */
public class CxProjectResult implements Action {

    private AbstractProject owner;

    public CxProjectResult(AbstractProject owner)
    {
        assert owner != null : "owner must not be null";
        this.owner = owner;
    }

    public CxScanResult getLastBuildAction()
    {
        AbstractBuild<?, ?> r = this.owner.getLastBuild();
        while (r != null)
        {

            CxScanResult a = r.getAction(CxScanResult.class);
            if (a!=null)
            {
                return a;
            }
            r = r.getPreviousBuild();
        }
        return null;
    }

    @Override
    public String getUrlName() {
        return "checkmarx";
    }

    @Override
    public String getDisplayName() {
        return "Checkmarx Scan Results";
    }

    @Override
    public String getIconFileName() {
        return getIconPath() + "CxIcon24x24.png";
    }

    public String getIconPath() {
        PluginWrapper wrapper = Hudson.getInstance().getPluginManager().getPlugin(CxPlugin.class);
        return "/plugin/"+ wrapper.getShortName()+"/";
    }

    public boolean isResultAvailable()
    {
        return getLastBuildAction() != null;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////
    // Graph generation logic
    ///////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Generates a PNG image for the test result trend.
     */
    public void doGraph( StaplerRequest req, StaplerResponse rsp) throws IOException {
        if(ChartUtil.awtProblemCause!=null) {
            // not available. send out error message
            rsp.sendRedirect2(req.getContextPath()+"/images/headless.png");
            return;
        }

        CxScanResult cxScanResult = getLastBuildAction();
        if(cxScanResult!=null && req.checkIfModified(cxScanResult.owner.getTimestamp(),rsp))
        {
            return;
        }

        ChartUtil.generateGraph(req,rsp,createChart(req,buildDataSet(req)),calcDefaultSize());
    }

    /**
     * Generates a clickable map HTML for {@link #doGraph(StaplerRequest, StaplerResponse)}.
     */
    public void doGraphMap( StaplerRequest req, StaplerResponse rsp) throws IOException {
        CxScanResult cxScanResult = getLastBuildAction();
        if(cxScanResult!=null && req.checkIfModified(cxScanResult.owner.getTimestamp(),rsp))
        {
            return;
        }
        ChartUtil.generateClickableMap(req,rsp,createChart(req,buildDataSet(req)),calcDefaultSize());
    }


    /**
     * Determines the default size of the trend graph.
     *
     * This is default because the query parameter can choose arbitrary size.
     * If the screen resolution is too low, use a smaller size.
     */
    private Area calcDefaultSize() {
        Area res = Functions.getScreenResolution();
        if(res!=null && res.width<=800)
            return new Area(250,100);
        else
            return new Area(500,200);
    }

    private CategoryDataset buildDataSet(StaplerRequest req) {

        CxScanResult lastBuildAction = getLastBuildAction();
        if (lastBuildAction==null)
        {
            // We get here is there are no builds with scan results.
            // In this case we generate an empty graph
            DataSetBuilder<CxResultSeverity,String> dsb = new DataSetBuilder<CxResultSeverity,String>();

            dsb.add( 0, CxResultSeverity.HIGH,   "0");
            dsb.add( 0, CxResultSeverity.MEDIUM, "0");
            dsb.add( 0, CxResultSeverity.LOW,    "0");
            return dsb.build();

        }  else {
            DataSetBuilder<CxResultSeverity,ChartUtil.NumberOnlyBuildLabel> dsb = new DataSetBuilder<CxResultSeverity,ChartUtil.NumberOnlyBuildLabel>();

            for( CxScanResult a=lastBuildAction; a!=null; a=a.getPreviousResult() ) {
                dsb.add( a.getHighCount(),   CxResultSeverity.HIGH,   new ChartUtil.NumberOnlyBuildLabel(a.owner));
                dsb.add( a.getMediumCount(), CxResultSeverity.MEDIUM, new ChartUtil.NumberOnlyBuildLabel(a.owner));
                dsb.add( a.getLowCount(),    CxResultSeverity.LOW,    new ChartUtil.NumberOnlyBuildLabel(a.owner));
            }
            return dsb.build();
        }

    }

    private JFreeChart createChart(StaplerRequest req,CategoryDataset dataset) {

        final String relPath = getRelPath(req);

        final JFreeChart chart = ChartFactory.createStackedAreaChart(
                null,                   // chart title
                null,                   // unused
                "count",                  // range axis label
                dataset,                  // data
                PlotOrientation.VERTICAL, // orientation
                true,                     // include legend
                true,                     // tooltips
                false                     // urls
        );

        // NOW DO SOME OPTIONAL CUSTOMISATION OF THE CHART...

        // set the background color for the chart...

        chart.setBackgroundPaint(Color.white);

        final CategoryPlot plot = chart.getCategoryPlot();

        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlinePaint(null);
        plot.setForegroundAlpha(0.8f);
        plot.setRangeGridlinesVisible(true);
        plot.setRangeGridlinePaint(Color.black);

        CategoryAxis domainAxis = new ShiftedCategoryAxis(null);
        plot.setDomainAxis(domainAxis);
        domainAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_90);
        domainAxis.setLowerMargin(0.0);
        domainAxis.setUpperMargin(0.0);
        domainAxis.setCategoryMargin(0.0);

        final NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        StackedAreaRenderer ar = new StackedAreaRenderer2() {
            @Override
            public String generateURL(CategoryDataset dataset, int row, int column) {
                ChartUtil.NumberOnlyBuildLabel label = (ChartUtil.NumberOnlyBuildLabel) dataset.getColumnKey(column);
                return relPath+label.build.getNumber()+"/testReport/";  //TODO: Check if need to change testReport to checkmarx
            }

            @Override
            public String generateToolTip(CategoryDataset dataset, int row, int column) {
                ChartUtil.NumberOnlyBuildLabel label = (ChartUtil.NumberOnlyBuildLabel) dataset.getColumnKey(column);
                AbstractTestResultAction a = label.build.getAction(AbstractTestResultAction.class);
                switch (row) {
                    case 0:
                        return String.valueOf(hudson.tasks.test.Messages.AbstractTestResultAction_fail(label.build.getDisplayName(), a.getFailCount()));
                    case 1:
                        return String.valueOf(hudson.tasks.test.Messages.AbstractTestResultAction_skip(label.build.getDisplayName(), a.getSkipCount()));
                    default:
                        return String.valueOf(hudson.tasks.test.Messages.AbstractTestResultAction_test(label.build.getDisplayName(), a.getTotalCount()));
                }
            }
        };
        plot.setRenderer(ar);
        ar.setSeriesPaint(0,new Color(246,0,22)); // high.
        ar.setSeriesPaint(1,new Color(249,167,16)); // medium.
        ar.setSeriesPaint(2,new Color(254,255,3)); // low.

        // crop extra space around the graph
        plot.setInsets(new RectangleInsets(0,0,0,5.0));

        return chart;
    }

    private String getRelPath(StaplerRequest req) {
        String relPath = req.getParameter("rel");
        if(relPath==null)   return "";
        return relPath;
    }

    @Extension
    public static class Factory extends TransientProjectActionFactory {

        private static final Logger logger = Logger.getLogger(Factory.class);

        /**
         * This factory method is called by Jenkins to create instances of CxProjectResult
         * for every project in the system.
         */
        @Override
        public Collection<? extends Action> createFor(AbstractProject project) {
            if (!(project instanceof MatrixProject))
            {
                // We don't want to add the CxProectResult action to MatrixProject,
                // since it does not make sense to present our vulnerability graph in this level
                LinkedList<Action> list = new LinkedList<Action>();
                list.add(new CxProjectResult(project));
                return list;
            }
            return null;
        };
    };
}

