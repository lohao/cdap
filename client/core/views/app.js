//
// App Status View
//

define([
	'lib/text!../partials/app.html'
	], function (Template) {
	
	return Em.View.extend({
		template: Em.Handlebars.compile(Template)
	});
});