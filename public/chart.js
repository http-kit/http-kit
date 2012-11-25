(function () {

  var $i = $('#i'),
      $history = $('#history');

  var id = 1;

  function add_msg (msg) {

    var now = Math.round(new Date().getTime() / 1000);
    var t = (now - msg.time) + 's ago';

    t = ["<span class=\"time\">", t + "</span>"].join('');
    var author = ["<span class=\"author\">",
                  msg.author,
                  "</span>: "].join('');
    // console.log(msg, ymdate(msg.time));
    $history.append('<li>' + author + msg.msg + t +'</li>');
    $history.find('li:last')[0].scrollIntoView();
  }

  function send_to_server () {
    var msg = $.trim($i.val()),
        author = $.trim($('#name').val() || 'anonymous');
    if(msg) {
      $.post("/msg", {msg: msg, author: author}, function (resp) {
        handle_msgs(resp);
        $i.val('');
      });
    }
  }

  function handle_msgs (msgs) {
    for(var i = 0; i < msgs.length; i++) {
      var msg = msgs[i];
      if(msg.id > id) {
        add_msg(msg);
        id = msg.id;
      }
    }
  }

  $('#send').click(send_to_server);

  $i.keyup(function (e) {
    if(e.which === 13) {        // enter
      send_to_server();
    }
  });

  (function polling_msgs () {
    $.get('/poll?id=' + id, function (resp) {
      handle_msgs(resp);
      polling_msgs();
    });
  })();

  $i.focus();
})();


var conn;

function open_con () {
  conn = new WebSocket("ws://127.0.0.1:9898/ws");

  conn.onopen = function (e) {
    console.log("open", e);
    conn.send("1234567890");
  };

  conn.onclose = function (e) {
    // console.log("close", e);
  };
  conn.onmessage = function (e) {
    console.log("message", e.data, e);
    // add_msg(e.data);
  };
}