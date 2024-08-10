<?php
    // AE2 Server endpoint (http://host:port/)
    $AE2_SERVER_HOST = "http://my.minecraftserver.com:2324/"; // slash at the end is important
    // AE2 Server password (MUST BE THE SAME AS ON THE SERVER)
    $AE2_SERVER_PASSWORD = "";

    // basic authoarization (the same authoarization as on the ae server)
    if (!isset($_SERVER['PHP_AUTH_USER']) || $_SERVER['PHP_AUTH_PW'] != $AE2_SERVER_PASSWORD) {
        header('WWW-Authenticate: Basic realm="AE2 Panel, please login"');
        header('HTTP/1.0 401 Unauthorized');
        exit;
    }

    // API proxy
    if (isset($_GET['API'])){
        $api_path = $_GET['API'];
        unset($_GET['API']);
        $params = $_GET;

        $ch = curl_init($AE2_SERVER_HOST . $api_path . '?' . http_build_query($params));
        curl_setopt($ch, CURLOPT_HTTPHEADER, array('Content-Type: application/xml'));
        curl_setopt($ch, CURLOPT_HEADER, FALSE);
        curl_setopt($ch, CURLOPT_USERPWD, 'a:' . $AE2_SERVER_PASSWORD); // authoarization is required on every request
        curl_setopt($ch, CURLOPT_TIMEOUT, 30);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, TRUE);
        $return = curl_exec($ch);
        curl_close($ch);
    
        print($return);

        exit;
    }
    
    // website
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        *,html,body{
            margin: 0;
            padding: 0;
        }
        body{
            background-color: #402E7A;
        }
        html,body{
            height: 100%;
            width: 100%;
        }
        table,tr,td{
            border: 2px, solid, black;
            border-collapse: collapse;
        }
        td{
            width: 200px;
            height: 50px;
            padding: 10px;
        }
        td.active{
            background-color: greenyellow;
        }
        td.pending{
            background-color: yellow;
        }
        td.storage{
            background-color: gainsboro;
        }
        td.missing{
            background-color: darkred;
        }
        #aecontent{
            /* min-width: calc(200px * 3 + 20px * 3); */
            margin: auto;
        }
    </style>
    <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.7.1/jquery.min.js"></script>
    <title>AE2</title>
</head>
<body>
<h1>AE2</h1>
<select id="clusters" onchange="selectCPU(this.value);">
    <option value="">(TERMINAL)</option>
</select>
<button onclick="selectCPU(document.getElementById('clusters').value);">Refresh</button>
<input type="checkbox" id="autorefresh"> Auto refresh
<section id="aecontent"></section>
<script>
    globalItemList = {};
    currentlyInOrderingScreen = false;
    currentJob = -1;
    function updateCPUList(){
        $.getJSON('list', function(data) {
            clusters = data['clusters'];
            let clustersDOM = document.getElementById("clusters");
            let oldSelected = clustersDOM.selectedIndex;
            while (clustersDOM.length > 1){
                clustersDOM.remove(clustersDOM.length-1);
            }
            for(let key in clusters){
                let cluster = clusters[key];
                let option = document.createElement("option");
                option.text = key;
                option.value = key;
                if(cluster['finalOutput']){
                    option.text += " - " + cluster['finalOutput']['itemname'] + ' x' + cluster['finalOutput']['quantity'];
                }
                clustersDOM.add(option);
            }
            clustersDOM.selectedIndex = oldSelected;
        });
    }
    updateCPUList();
    function getItemList(){
        $.getJSON('items', function(data){
            console.log(data);
            if (data['items']){
                globalItemList = data['items'];
                let html = "<table>";
                let grid_i_max = 8;
                let grid_i = 0;
                html += "<tr>";
                let items = data['items'];
                for(let i = 0; i < items.length; i++){
                    let item = items[i];
                    html += "<td class='storage'>" + item['itemname'] + "<br>Stored: " + item['quantity'] + (item['craftable'] ? '<br><button onclick="beginOrderingItem(' + item['hashcode'] + ');">order</button>' : '') + "</td>";
                    grid_i++;
                    if(grid_i == grid_i_max){
                        html += "</tr><tr>";
                        grid_i = 0;
                    }
                }
                for(; grid_i < grid_i_max; grid_i ++){
                    html += "<td></td>";
                }
                html += "</tr>";
                document.getElementById("aecontent").innerHTML = html;
            }
        });
    }
    getItemList();
    function beginOrderingItem(hashcode){
        console.log(hashcode);
        currentlyInOrderingScreen = true;
        let quantity = window.prompt("How much to order?", "1");
        if (quantity == null || quantity == ""){
            currentlyInOrderingScreen = false;
        }
        else {
            document.getElementById("aecontent").innerHTML = "<h1>SENDING REQUEST</h1>";
            $.getJSON('order?item=' + hashcode + "&quantity=" + quantity, function(data){
                console.log(data);
                if (data['jobID']){
                    document.getElementById("aecontent").innerHTML = "<h1>SIMULATING CRAFTING</h1>";
                    currentJob = data['jobID'];
                    setTimeout(updateCraftingPlan, 1000);
                }
                else{
                    currentlyInOrderingScreen = false;
                    getItemList();
                }
            });
        }
    }
    function updateCraftingPlan(){
        if(!currentlyInOrderingScreen || currentJob == -1){
            currentlyInOrderingScreen = false;
            currentJob = -1;
            return;
        }
        $.getJSON('job?id=' + currentJob, function(data){
            console.log(data);
            if (data['jobIsDone']){
                let html = "";
                let jobData = data['jobData'];
                if (jobData['isSimulating'])
                    html += "<h1>SIMULATING CRAFTING - TOTAL COST: " + jobData['bytesTotal'] + "</h1><button onclick='cancelCurrentJob();'>CANCEL</button>";
                else
                    html += "<h1>TOTAL COST: " + jobData['bytesTotal'] + "</h1><button onclick='startCurrentJob();'>START</button><button onclick='cancelCurrentJob();'>CANCEL</button>";
                if (jobData['plan']){
                    html += "<br><br><table><tr>";
                    let grid_i_max = 8;
                    let grid_i = 0;
                    let items = jobData['plan'];
                    for(let i = 0; i < items.length; i++){
                        let item = items[i];

                        html += "<td class='" + (item['missing'] > 0 ? 'missing' : 'storage') + "'>" + item['itemname'];
                        if (item['missing'] > 0)
                            html += "<br>Missing: " + item['missing'];
                        if (item['requested'] > 0)
                            html += "<br>To craft: " + item['requested'];
                        if (item['steps'] > 0)
                            html += "<br>Steps: " + item['steps'];
                        if (item['stored'] > 0)
                            html += "<br>Available: " + item['stored'];
                        html += "</td>";

                        grid_i++;
                        if(grid_i == grid_i_max){
                            html += "</tr><tr>";
                            grid_i = 0;
                        }
                    }
                    for(; grid_i < grid_i_max; grid_i ++){
                        html += "<td></td>";
                    }
                    html += "</tr></table>";
                }
                console.log(html);
                document.getElementById("aecontent").innerHTML = html;
            }
            else{
                setTimeout(updateCraftingPlan, 1000);
            }
        });
    }
    function cancelCurrentJob(){
        if(!currentlyInOrderingScreen || currentJob == -1){
            currentlyInOrderingScreen = false;
            currentJob = -1;
            return;
        }
        $.getJSON('job?id=' + currentJob + "&cancel", function(data){
            currentJob = -1;
            currentlyInOrderingScreen = false;
            getItemList();
        });
    }
    function startCurrentJob(){
        if(!currentlyInOrderingScreen || currentJob == -1){
            currentlyInOrderingScreen = false;
            currentJob = -1;
            return;
        }
        $.getJSON('job?id=' + currentJob + "&submit", function(data){
            if (data['jobSubmissionFailureMessage']){
                window.alert(data['jobSubmissionFailureMessage']);
            }
            currentJob = -1;
            currentlyInOrderingScreen = false;
            updateCPUList();
            getItemList();
        });
    }
    function cancelJobOnCPU(selectedCPU){
        if (selectedCPU == ""){
            return;
        }
        $.getJSON('cancelcpu?cpu=' + encodeURIComponent(selectedCPU).replace(/'/g,"%27").replace(/"/g,"%22"), function(data){
            updateCPUList();
            selectCPU(selectedCPU);
        });
    }
    function selectCPU(selectedCPU){
        if (selectedCPU == ""){
            getItemList();
            return;
        }
        $.getJSON('get?cpu=' + encodeURIComponent(selectedCPU).replace(/'/g,"%27").replace(/"/g,"%22"), function(data){
            console.log(data);
            let html = "";
            if (data['finalOutput']){
                html += "<h1>Crafting: " + data['finalOutput']['itemname'] + " x" + data['finalOutput']['quantity'] + "</h1><br><button onclick='cancelJobOnCPU(\"" + selectedCPU + "\");'>CANCEL</button><br>";
            }else{
                html += "<h1>IDLE</h1><br><br>";
            }
            html += "<table><tr>";
            let grid_i_max = 8;
            let grid_i = 0;
            if(data['items']){
                let items = data['items'];
                for(let i = 0; i < items.length; i++){
                    let item = items[i];
                    let el = '';
                    if(item['active'] > 0)
                        el = 'active';
                    else if(item['pending'] > 0)
                        el = 'pending';
                    else
                        el = 'storage';
                    html += "<td class='" + el + "'>" + item['itemname'] + "<br>Crafting: " + item['active'] + "<br>Scheduled: " + item['pending'] + "<br>Stored: " + item['stored'] + "</td>";
                    grid_i++;
                    if(grid_i == grid_i_max){
                        html += "</tr><tr>";
                        grid_i = 0;
                    }
                }
            }
            for(; grid_i < grid_i_max; grid_i ++){
                html += "<td></td>";
            }
            html += "</tr></table>";
            document.getElementById("aecontent").innerHTML = html;
        });
    }
    function autoRefresher(){
        setTimeout(autoRefresher, 10000);
        if (currentlyInOrderingScreen) return;
        updateCPUList();
        if(document.getElementById('autorefresh').checked){
            selectCPU(document.getElementById('clusters').value);
        }
    }
    setTimeout(autoRefresher, 10000);
</script>
</body>
</html>
