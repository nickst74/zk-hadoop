var all_data;

/* Filter variables */

function parse_events(events) {
    const data = [];
    events.forEach(event => {
        const bc_addr = event.returnValues.datanode.toLowerCase();
        const ip = bc_to_ip[bc_addr];
        const time = format_time(event.returnValues.time);
        const time_dnode = time + ' @ ' + (ip === undefined ? 'N/A' : ip) + '\n' + bc_addr;
        event.returnValues.blocks.forEach(blockId => {
            const file = block_to_file[blockId];
            var blockIdx, fileBlocks;
            if(file !== undefined) {
                blockIdx = file_to_blocklist[file].indexOf(blockId) + 1;
                fileBlocks = file_to_blocklist[file].length;
            }
            const wrong = event.returnValues.corrupted.includes(blockId);
            data.push({
                'time_dnode': time_dnode,
                'blockId': blockId,
                'file': file,
                'blockIdx': blockIdx,
                'fileBlocks': fileBlocks,
                'wrong': wrong
            });
        });
    });
    return data;
}


/*  CHART CREATION AND CUSTOMIZATION  */
am4core.ready(function() {

    $('#selectors').hide();

    am4core.useTheme(am4themes_animated);

    chart = am4core.create("chartdiv", am4charts.XYChart);
    chart.maskBullets = false;

    var xAxis = chart.xAxes.push(new am4charts.CategoryAxis());
    var yAxis = chart.yAxes.push(new am4charts.CategoryAxis());

    xAxis.dataFields.category = "blockId";
    yAxis.dataFields.category = "time_dnode";

    xAxis.renderer.grid.template.disabled = true;
    xAxis.renderer.minGridDistance = 100;
    xAxis.renderer.cellStartLocation = 0.2;
    xAxis.renderer.cellEndLocation = 0.8;

    yAxis.renderer.grid.template.disabled = true;
    yAxis.renderer.inversed = false;
    yAxis.renderer.minGridDistance = 30;
    yAxis.renderer.cellStartLocation = 0.2;
    yAxis.renderer.cellEndLocation = 0.8;

    var series = chart.series.push(new am4charts.ColumnSeries());
    series.dataFields.categoryX = "blockId";
    series.dataFields.categoryY = "time_dnode";
    series.dataFields.wrong = "wrong";
    series.dataFields.file = "file";
    series.sequencedInterpolation = true;
    series.defaultState.transitionDuration = 2000;

    // adapters for the color filling and opacity of the items
    series.columns.template.adapter.add('fill', (fill, target) => {
        if(target.dataItem) {
            if(target.dataItem.file !== undefined) {
                return am4core.color(target.dataItem.wrong ? '#C41919' : '#008000');
            }
            return am4core.color(target.dataItem.wrong ? '#ff8181' : '#88ee88');
        }
        return fill;
    });

    // Add a drop shadow filter on columns
    var shadow = series.columns.template.filters.push(new am4core.DropShadowFilter);
    shadow.opacity = 0.1;

    // Create hover state
    var hoverState = series.columns.template.states.create("hover");
    hoverState.properties.fill = am4core.color("#396478");
    hoverState.properties.dx = -5;
    hoverState.properties.dy = -5;

    // Slightly shift the shadow and make it more prominent on hover
    var hoverShadow = hoverState.filters.push(new am4core.DropShadowFilter);
    hoverShadow.dx = 6;
    hoverShadow.dy = 6;
    hoverShadow.opacity = 0.3;

    series.columns.template.events.on('hit', function(ev) {
        // TODO: check if the file exists in the hdfs
        if(ev.target._dataItem._dataContext.file !== undefined) {
            var filename = ev.target._dataItem._dataContext.file;
            window.location.href = "explorer.html#" + filename;
        }
    });

    var bgColor = new am4core.InterfaceColorSet().getFor("background");
    var columnTemplate = series.columns.template;
    columnTemplate.cursorOverStyle = am4core.MouseCursorStyle.pointer;
    columnTemplate.strokeWidth = 1;
    columnTemplate.strokeOpacity = 0.2;
    columnTemplate.stroke = bgColor;
    //columnTemplate.propertyFields.fill = "color";
    columnTemplate.column.cornerRadius(6, 6, 6, 6);
    columnTemplate.adapter.add('tooltipHTML', (tooltipHTML, target) => {
        if(target) {
            var ret = '<center><strong>Block #{blockId}</strong></center>';
            if(target.dataItem.file !== undefined) {
                return ret + '<center>Block <strong>{blockIdx}/{fileBlocks}</strong> of file <span style="font-family:monospace">{file}</span></center>';
            }
            return ret + "<center>File N/A</center>";
        }
        return tooltipHTML;
    })
    /*tooltipHTML = `<center><strong>Block #{blockId}</strong></center>
    <center>
        Block
        <strong>{blockIdx}/{fileBlocks}</strong>
        of file
        <span style="font-family:monospace">{file}</span>
    </center>`;*/
    columnTemplate.width = am4core.percent(80);
    columnTemplate.height = am4core.percent(80);

    series.heatRules.push({
        target: columnTemplate,
        property: "fill",
    });

    // Add scrollbar
    function add_scrollbar(axis) {
        axis.startGrip.background.fill = am4core.color("#CBA5A4");
        axis.endGrip.background.fill = am4core.color("#CBA5A4");
        axis.thumb.background.fill = am4core.color("#CBA5A4");

        axis.startGrip.icon.stroke = am4core.color("#8A5658");
        axis.endGrip.icon.stroke = am4core.color("#8A5658");

        // Applied on hover
        axis.startGrip.background.states.getKey("hover").properties.fill = am4core.color("#BC8C8A");
        axis.endGrip.background.states.getKey("hover").properties.fill = am4core.color("#BC8C8A");
        axis.thumb.background.states.getKey("hover").properties.fill = am4core.color("#BC8C8A");

        // Applied on mouse down
        axis.startGrip.background.states.getKey("down").properties.fill = am4core.color("#AD7371");
        axis.endGrip.background.states.getKey("down").properties.fill = am4core.color("#AD7371");
        axis.thumb.background.states.getKey("down").properties.fill = am4core.color("#AD7371");
    }

    // Get events at startup and add the to chart
    blockchain(parse_events).then(data => {
        all_data = data;
        chart.data = all_data;
        $('#selectors').show();
        chart.scrollbarX = new am4core.Scrollbar();
        add_scrollbar(chart.scrollbarX);
    });
    

    var cellSize = 20;
    chart.events.on('datavalidated', function(ev) {
        // Get objects of interest
        var chart = ev.target;
        var categoryAxis = chart.yAxes.getIndex(0);
        // Calculate how we need to adjust chart height
        var adjustHeight = chart.data.length * cellSize - categoryAxis.pixelHeight;
        // get current chart height
        var targetHeight = chart.pixelHeight + adjustHeight;
        // Set it on chart's container
        chart.svgContainer.htmlElement.style.height = targetHeight + 'px';
    });

});
/* END OF CHART CREATION STAFF */

/* UPDATE CHART DATA */
function update_chart() {
    
}