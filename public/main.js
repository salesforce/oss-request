$(function () {

  $('#myModal').on('show.bs.modal', function (event) {
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

});
