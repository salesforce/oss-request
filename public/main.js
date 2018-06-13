$(function () {

  $('#commentsModal').on('show.bs.modal', function (event) {
    var button = $(event.relatedTarget);
    var modal = $(this);
    modal.find('.modal-body').empty();
    modal.find('.modal-footer').children().hide();

    var requestSlug = button.data('requestslug');
    var taskId = button.data('taskid');

    if (taskId != null) {
      modal.find('.modal-title').text('Comments');

      var commentsUrl = '/request/' + requestSlug + '/task/' + taskId + '/comments';

      var newCommentForm = $('#newComment');

      newCommentForm.attr('action', commentsUrl);
      newCommentForm.show();

      $.get(commentsUrl, function (data) {
        modal.find('.modal-body').html(data);
      });
    }
  });

  $('#confirmModal').on('show.bs.modal', function (event) {
    var button = $(event.relatedTarget);
    var modal = $(this);

    var action = button.data('action');
    var url = button.data('url');

    $("#confirmButton").click(function () {
      $.ajax({
        url: url,
        type: action,
        success: function() {
          location.reload();
        },
        error: function (error) {
          var body = $(".modal-body").html(error.responseText);
          modal.find('.modal-header').after(body);
        }
      });
    });
  });

});
