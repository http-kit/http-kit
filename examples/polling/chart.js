(function () {
  var $i = $('#i'),
      $history = $('#history');

  var max_id = 1;

  function add_msg (msg) {

    var now = Math.round(new Date().getTime() / 1000);
    var t = (now - msg.time) + 's ago';

    t = ["<span class=\"time\">", t + "</span>"].join('');
    var author = ["<span class=\"author\">",
                  msg.author,
                  "</span>: "].join('');
    // console.log(msg, ymdate(msg.time));
    $history.append('<li>' + author + msg.msg + t +'</li>');
  }

  function send_to_server () {
    var msg = $.trim($i.val()),
        author = $.trim($('#name').val() || 'anonymous');
    if(msg) {
      $.post("/msg", {msg: msg, author: author}, function (resp) {
        $i.val('');
      });
    }
  }

  $('#send').click(send_to_server);

  $i.keyup(function (e) {
    if(e.which === 13) {        // enter
      send_to_server();
    }
  });

  (function polling_msgs () {
    $.get('/poll?id=' + max_id, function (msgs) {
      for(var i = 0; i < msgs.length; i++) {
        var msg = msgs[i];
        if(msg.id > max_id) {
          add_msg(msg);
          max_id = msg.id;
        }
      }
      polling_msgs();
    });
  })();

  $i.focus();
})();
