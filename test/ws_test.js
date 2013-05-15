var con = new WebSocket("ws://192.168.1.101:9090/bug");

con.onopen = function(){
  /*Send a small message to the console once the connection is established */
  console.log('Connection open!');
}

con.onclose = function(){
  console.log('Connection closed');
}

con.onerror = function(error){
  console.log('Error detected: ' + error);
}


con.onmessage = function(e){
  var server_message = e.data;
  console.log(server_message);
}